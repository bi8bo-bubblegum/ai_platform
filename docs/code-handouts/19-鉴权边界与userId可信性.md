# 鉴权边界与 `userId` 的可信性

> 触发这个问题：`anonymous` 兜底会让未带用户标识的请求共用记忆空间。
> 你的反驳是"真实业务肯定必须登录才能访问，是不是就不会出现这个问题了"。
>
> **答案是：理想情况下成立，但要先确认两个前提。其中第二个前提如果不成立，
> 问题的严重性比"匿名共用记忆"高一个量级 —— 而且它不会因为"必须登录"而消失。**

---

## 一、先分清两个不同的问题

你说的"必须登录"解决的是**第一个**，但**第二个**才是关键：

| # | 问题 | "必须登录"能解决吗 |
|---|---|---|
| 1 | **没登录的人能访问** → 兜底成 anonymous → 互串记忆 | ✅ 能（前提是所有流量必经网关） |
| 2 | **登录了的人能伪造别人的 userId** → 越权读别人的记忆 | ❌ **不能** |

第 2 个问题的形式是：

```bash
# 我正常登录了，拿到自己的 token
curl -N "http://网关/chat/stream?sessionId=s1&message=我是谁" \
     -H "Authorization: Bearer <我的token>" \
     -H "X-User-Id: 别人的ID"        # ← 如果网关只是透传，这一行就能读到别人的记忆
```

而事实是**用户级的**、跨助手共享的（这是刻意的设计）——
所以拿到别人的 userId，等于拿到那个人的完整画像。

**这个问题的答案不在你的代码里，在网关那边。** 见第四节的确认清单。

---

## 二、前提一：所有流量必经网关（这不构成"代码不用管"的理由）

**你说得对的地方**：如果

- 服务只暴露在网关之后（K8s 里 Service 只在集群内、没有 NodePort/LoadBalancer）
- 网关对所有路由强制鉴权
- 没有别的调用路径

那么 `X-User-Id` **必然存在**，`anonymous` 分支走不到。

**但这三层里任何一层被打破，问题就出现**，而且都是很常见的运维场景：

| 打破方式 | 发生的频率 |
|---|---|
| 网关路由配置漏了某个新加的接口 | 很常见（新接口上线时忘了加鉴权规则） |
| 内网有人直连服务调试（"我 curl 一下"） | 很常见 |
| K8s 里别的服务直接调 `http://assistant-demo:8080/...` | 常见（服务间调用往往绕过网关） |
| 本地开发时起服务，忘了带鉴权 | 每天 |
| 网关本身故障 / 被临时降级 / 灰度版本没带鉴权插件 | 不常见但有 |

**关键不是"哪一天会出问题"，而是"出了问题不会报错"。**
`anonymous` 是**静默兜底** —— 没有日志、没有告警，只是两个用户的记忆悄悄合在一起。
等发现的时候，`ai_user_memory` 里已经混进了无法区分归属的数据。

> 这就是**纵深防御（defense in depth）**的思路：
> 网关鉴权是**第一道**，服务自身的校验是**第二道**。
> **"第一道存在"不构成"第二道不需要"的理由** —— 因为第一道在运维层面是可能被绕过的，
> 而第二道在代码层面是不可绕过的。

**成本对比**也很清楚：

| | |
|---|---|
| 第二道防线的成本 | 3 行代码 + 一个配置开关 |
| 它防的东西 | 数据污染（不可逆、难发现、清理成本高） |

---

## 三、前提二：`X-User-Id` 必须是网关**覆写**的，不是**透传**的

**这一条比前提一重要得多，而且我怀疑你还没确认过。**

两种网关实现方式，安全性的差别是本质性的：

| 方式 | 行为 | 后果 |
|---|---|---|
| ❌ **透传** | 网关鉴权后，把客户端传来的 `X-User-Id` **原样转发**给后端 | **客户端可以伪造任何 userId** → 越权读任意人的记忆 |
| ✅ **覆写** | 网关鉴权后，**从 token/JWT 里解析出真实用户**，用它**覆盖**客户端传来的头 | 客户端伪造无效 ✓ |

**判断方法**（你可以自己先试，不用等网关的人）：

```bash
# 带着自己的 token，但传一个别人的（或不存在的）X-User-Id
curl -N "http://网关/chat/stream?sessionId=s1&message=我是谁" \
     -H "Authorization: Bearer <我的token>" \
     -H "X-User-Id: not-my-id"

# 然后查这张表：
# SELECT DISTINCT user_id FROM ai_token_usage ORDER BY id DESC LIMIT 5;
# 如果出现 not-my-id → 网关是【透传】，安全问题成立
# 如果出现的是你的真实 ID → 网关是【覆写】，这一条通过
```

> 用 token 台账查是因为它**每次都写**（不管记忆是否生效），是最快的探针。
> 会话记忆要等 Redis，长期记忆要等异步抽取，都慢。

**如果结果是"透传"，那么：**
- 加 401 检查**没有用**（userId 看起来完全合法）
- 唯一的修法是**让网关照做覆写**
- 在那之前，**这个服务不能对接任何真实用户数据**

---

## 四、给网关负责人的确认清单

这份可以原样发给他。前两条是**阻塞项**，不确认就没法谈后面。

| # | 问题 | 为什么重要 |
|---|---|---|
| **1** | `X-User-Id` 是**覆写**（从 token 解析后覆盖）还是**透传**（原样转发客户端的）？ | 决定"能否伪造 userId"。**如果是透传，SDK 侧无解** |
| **2** | 未登录/鉴权失败的请求，网关是**直接 401** 还是**放行到后端**？ | 决定 `anonymous` 分支会不会被走到 |
| **3** | 有没有**绕过网关**的调用路径？（集群内直连 Service、定时任务、其他服务调用） | 决定"第二道防线"是否必要。**如果有，就必须加** |
| 4 | 能否提供一个**「已鉴权」标记**（如 `X-Authenticated: true`，同样由网关覆写）？ | 后端可以据此拒绝"看着有 userId 但没经过鉴权"的请求 —— 这是比"检查 anonymous"更彻底的方案 |

> 第 4 条是标准做法：**后端不信任任何客户端可写的头，只信任网关覆写的头。**
> 有了这个标记，后端就能区分"经网关鉴权的请求"和"直连的请求"，
> 而且不需要写死 `anonymous` 这个特殊值。

**顺带一个反问**：如果网关是透传的，那 **token 台账也同样被污染** ——
`ai_token_usage.user_id` 会被写成伪造的值，按用户做的成本统计就全错了。
所以这件事**不只是记忆的问题，是整条链路的问题**。

---

## 五、务实方案：加开关，默认严格

我理解你担心的是"加了会不会影响本地开发"。所以不要做成无条件拒绝，做成**配置开关**：

### 5.1 `AiMemoryProperties` 加一个字段

路径：`ai-memory/src/main/java/com/best/ai_memory/config/AiMemoryProperties.java`

```java
@ConfigurationProperties("ai.memory")
public record AiMemoryProperties(
        @DefaultValue Session session,
        @DefaultValue User user,

        /**
         * 是否要求请求必须携带有效用户标识。
         *
         * <p><b>为什么默认 true：</b>
         * userId 参与拼 memoryId（{@code assistantId::userId::sessionId}），
         * 所以一旦缺失被兜底成 anonymous，所有未携带标识的请求会共用同一份
         * 会话历史和长期记忆 —— A 说"我叫小明"，B 问"我是谁"会答"小明"。
         * 而且它【静默发生】，要等到有人抱怨"它答的这不是我"才会发现。
         *
         * <p><b>什么时候设成 false：</b>
         * 本地开发 / 联调时（前端还没有登录态）。生产环境应保持 true ——
         * 它防的是"绕过网关直连服务"（网关配置漏了、内网 curl、集群内调用）。
         *
         * <p><b>注意这解决不了"伪造 userId"</b>：那个必须靠网关【覆写】X-User-Id。
         * 本开关只保证"必须有一个标识"，不保证"这个标识是真的"。
         */
        @DefaultValue("true") Boolean requireUserId
) { /* Session / User 不变 */ }
```

### 5.2 `ChatStreamController` 里加校验

```java
    private final AiMemoryProperties memoryProperties;   // 由 @RequiredArgsConstructor 注入

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message) {

        String userId = AiUserContext.get();

        // ⚠️ userId 参与拼 memoryId，所以它必须是【明确的、属于某个人的】值。
        //
        // 缺失时 AiUserContextInterceptor 会兜底成 anonymous，而所有 anonymous 请求
        // 会共用同一份记忆（A 说"我叫小明"，B 问"我是谁"会答"小明"），且静默发生。
        //
        // 默认拒绝（快速失败）。本地开发嫌麻烦就把 ai.memory.require-user-id 设为 false。
        if (Boolean.TRUE.equals(memoryProperties.requireUserId())
                && (userId == null || userId.isBlank() || AiUserContext.ANONYMOUS.equals(userId))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少用户标识（X-User-Id）。本服务不应直接暴露，请经网关访问；"
                            + "本地开发可设 ai.memory.require-user-id=false");
        }

        ...
```

import：

```java
import com.best.ai_memory.config.AiMemoryProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
```

### 5.3 `application.yml`

```yaml
ai:
  memory:
    # 生产保持 true；本地开发前端还没登录态时设 false
    require-user-id: true
```

---

## 六、我对"后期再说"这个判断的看法

你的推理是：**现在只有一个内部服务，后期接真实业务时必然要登录，所以现在不用管。**

**逻辑上成立，但有一个时间窗口的问题**——

从现在到"后期"之间，如果这个服务已经被内网访问（大概率会），那么：

| 影响面 | 后果 | 可逆吗 |
|---|---|---|
| 会话记忆（Redis） | 串了 | ✅ 可逆，TTL 48h 后自动消失 |
| **长期记忆（MySQL）** | **串了** | ❌ **不可逆** |
| token 台账（MySQL） | `user_id` 记成 anonymous 或伪造值 | ⚠️ 半可逆（成本统计失真，但数据还在） |

**长期记忆为什么不可逆**：它是**永久保存**的。一旦 A 的事实和 B 的事实混进了同一个
`user_id` 下，你**无法事后区分哪条是谁的** —— 因为脏数据和正常数据长得完全一样
（都是"用户在 XX 部门"这种句子）。

清理方法只有：发现异常 → 人工删掉那个用户的全部记忆 → 让他重新说。
**而"发现异常"这一步，靠的是用户抱怨。**

所以我的建议不是"现在就上鉴权"，而是：

**成本 3 行的开关，为什么不现在就加上？** 它不阻塞任何事（本地可以关），
但它把"静默污染数据"变成了"明确报错"。

---

## 七、一句话总结

> **"必须登录"能防住"没登录的人"，但防不住"登录了的人伪造别人的 ID"。
> 而后者才是这个设计里最危险的那个 —— 因为长期记忆是永久保存、事后无法区分归属的。**
>
> 加个默认严格的开关（3 行），然后去找网关确认两件事：
> **① X-User-Id 是覆写还是透传？② 有没有绕过网关的调用路径？**

---

## 八、如果决定暂缓（当前采用）

**这个决定是合理的**：现在没有真实用户数据，`anonymous` 混用只发生在自己的调试请求之间，
会话记忆串了也有 TTL 兜底。接入鉴权时再一起做，比现在做一半更省事。

**但建议不要让"暂缓"变成"代码里没有这个概念"** —— 那样它就从"待办"变成了"遗忘"。
推荐的最小形态：**不改任何拦截逻辑，只加一个启动检查，把待办放进日志**。

### 8.1 启动检查（完整代码）

路径：`assistant-demo/src/main/java/com/best/assistant_demo/config/MemoryGuardStartupCheck.java`

```java
package com.best.assistant_demo.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时的记忆安全提醒。
 *
 * <p><b>它不做任何拦截</b>，也不影响任何请求 —— 只是把一条待办放进日志，
 * 让"以后要处理"这件事不依赖人的记忆。
 *
 * <p>当前状态：记忆功能【不校验】用户标识。请求缺少 {@code X-User-Id} 时
 * {@code AiUserContextInterceptor} 会兜底成 {@code anonymous}，而 userId 参与拼
 * memoryId，所以所有匿名请求会共用同一份会话历史和长期记忆。
 *
 * <p>现在只在内部使用，可以接受。但【接入真实用户数据之前】必须处理 ——
 * 所以让这条日志在每次启动时出现一次。
 *
 * <p>处理完之后把这个类删掉。
 */
@Slf4j
@Component
public class MemoryGuardStartupCheck {

    @Value("${ai.memory.assistant-id:${spring.application.name:unknown}}")
    private String assistantId;

    @PostConstruct
    public void warnIfMemoryIsUnguarded() {
        log.warn("""

                ================================================================
                 记忆模块当前【不校验】用户标识

                 请求缺少 X-User-Id 时会兜底成 anonymous，而 userId 参与拼 memoryId，
                 所以所有匿名请求共用同一份记忆：
                     memoryId = {}::anonymous::<sessionId>

                 现在只在内部使用，可以接受。但【接入真实用户数据之前】必须做两件事
                 （顺序不能反）：

                   ① 先确认网关是【覆写】X-User-Id 而不是透传
                      透传的话客户端可以伪造这个头，登录也阻止不了越权读他人记忆；
                      那种情况下改后端代码是没用的。详见 docs/code-handouts/19

                   ② 再打开 ai.memory.require-user-id: true

                 处理完删掉这个类。在那之前，这条日志会每次启动都出现 —— 这是刻意的。
                ================================================================
                """, assistantId);
    }
}
```

**为什么值得花这 20 行**：

| | 不加 | 加了 |
|---|---|---|
| 待办在哪 | 在你脑子里 / 在这份文档里 | **在每次启动的日志里** |
| 三个月后新同事接手 | 不知道有这回事 | 启动就看到 |
| 忘记的代价 | 静默污染长期记忆（不可逆） | — |

> 如果觉得启动日志也算"打扰"，还有一个更轻的替代：什么都不加，
> 但**把这条记进项目的待办清单**（`.workbuddy/memory/` 里）。
> 两种都行，唯一不推荐的是"既不加也不记"。

### 8.2 明确触发条件：出现这些信号就必须回来做

| # | 信号 | 说明 |
|---|---|---|
| **1** | **第一个真实用户接入之前** | 最简单可靠的判据 |
| 2 | 服务给团队外部的人访问之前 | 哪怕只是"给人演示一下" |
| 3 | 内网开放访问之前 | 集群内其他服务能调到它 |

**判据的核心只有一句**：**除了你之外的任何人的数据，有没有进过这两张表**
（`ai_user_memory` / `ai_token_usage`）？

一旦有，就要按顺序做 8.1 里的两件事。

### 8.3 处理时的顺序不能反

```
① 先和网关确认：X-User-Id 是覆写还是透传
      ↓
   如果是透传 → 让网关照做覆写（这是前置条件，后端改代码解决不了）
      ↓
② 再打开 ai.memory.require-user-id: true
      ↓
③ 删掉 MemoryGuardStartupCheck
```

**为什么顺序不能反**：如果网关是透传的，打开 `require-user-id` 只会拦住"没带头的请求"，
而带了伪造头的请求照样通过 —— 那时候你会以为"已经处理了"，但实际没有。
**先确认信任链，再加检查。**

---

## 附：这一节的判断依据速查

| 说法 | 成立吗 |
|---|---|
| "加了鉴权，anonymous 分支就走不到" | ✅ 只要所有流量必经网关 |
| "所以代码里不用检查" | ❌ 第二道防线防的是"绕网关"，而绕网关是很常见的运维场景 |
| "必须登录，所以 userId 一定可信" | ❌ 取决于网关是覆写还是透传 —— **两件事** |
| "长期记忆串了可以事后清理" | ❌ 无法区分归属，只能整条删 |
| "现在只有内部服务，不急" | ⚠️ 看风险窗口 —— 如果内网已经能访问，窗口是开着的 |
