# 复核 · 全链路 token 与一个新引入的风险

> 复核对象：本轮改动的 5 个文件
> （`TokenUsageListener` / `UserMemoryRecorder` / `ChatStreamController` /
> `MemoryRecallService` / `RedisSessionSummaryStore`）

---

## 一、先说结论

| | |
|---|---|
| ✅ **改进 3 处，都准** | 其中 `onRequest` 的 null 兜底是**我漏掉的真 bug** |
| 🔴 **新引入风险 1 处** | 匿名用户会**共用同一个记忆空间** —— 建议优先修 |
| 🟡 **清理 4 处** | 一个残留变量、一个死方法、两个多余 import、一个上轮未落地的改动 |
| ⚪ **全链路缺口 1 处** | embedding 的 token 仍未统计（附判断依据） |

---

## 二、你做对的三处

### 2.1 `onRequest` 的 null 兜底 —— **这是我漏掉的真 bug**

```java
@Override
public void onRequest(ChatModelRequestContext requestContext) {
    Map<Object, Object> attributes = requestContext.attributes();
    String userId = AiUserContext.get();
    attributes.put(ATTR_USER_ID, userId == null || userId.isBlank() ? AiUserContext.ANONYMOUS : userId);
}
```

**你的注释完全正确**，而且这一点比表面看起来严重：

> `attributes` 是 `ConcurrentHashMap` —— **它不允许 null value，`put(key, null)` 会直接抛 `NullPointerException`。**

而这次调用发生在**框架的 `StreamingChatModel.chat()` 方法里**（字节码第 96 行那个
`ChatModelListenerUtils.onRequest`），也就是说：

**一个后台任务（或任何非请求线程）调用模型 → listener 的 onRequest 抛 NPE → 整个模型调用失败。**

我在第三轮提 `onRequest` 快照方案时，只验证了"onRequest 在调用线程、attributes 跨线程安全"，
**没有意识到 attributes 不接受 null** —— 而"非请求线程读 ThreadLocal 得到 null"恰好是常态
（比如你正在处理的抽取任务）。你的兜底把这个坑堵住了。

### 2.2 `AiUserContext.open` 包住异步任务 —— 写法正确

```java
taskExecutor.execute(() -> {
    try (AiUserContext.Scope ignored = AiUserContext.open(key.userId())) {
        record(key.assistantId(), key.userId(), key.sessionId(), recentTurns);
    } catch (Exception e) {
        log.error("记录用户记忆失败, userId={}", key.userId(), e);
    }
});
```

三点都对：

1. **用对了工具。** `AiUserContext.open()` 返回的 `Scope` 实现了 `AutoCloseable`，
   所以 `try-with-resources` 在**异常路径上也会恢复 ThreadLocal** —— 这正是它存在的意义。
   （顺带说明：我在第三轮说 `AiUserContext.wrap/open` 是"死代码、救不了 I/O 线程"——
   **那个结论对一半**。它确实救不了模型 I/O 线程，但**对"从请求线程 fork 到线程池的任务"
   正是它的主场**，你这个用法就是标准场景。）
2. **为什么必须有这一步。** `TaskExecutor`（Boot 的 `applicationTaskExecutor`）默认
   **不会传递 ThreadLocal**（除非配 `TaskDecorator`）。所以不显式 `open`，
   抽取任务的 token 会全部记成 `anonymous`。
3. **`previous` 的恢复语义是对的。** 线程池复用线程，`open` 会先存下旧值、
   `close` 时恢复 —— 即使将来有人在任务外层套了别的上下文也不会串。

**这一改同时修好了两件事**：抽取的 token 归属正确 + 抽取链路里任何走 ThreadLocal 的逻辑
（比如将来给 embedding 加 listener）都能拿到 userId。

### 2.3 `ChatStreamController` 改用 `AiUserContext.get()` —— 三处同源

```java
public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message){
    ...
    String userId = AiUserContext.get();
    String memoryId = SessionKey.of(assistantId, userId, sessionId).toMemoryId();
```

`userId` 不再从 query 参数来，而是从 `AiUserContext`（即 `X-User-Id` 请求头）来。
这样**会话记忆、长期记忆、token 台账三者用的是同一个 userId** ——
把 14 号那条"两套来源"的问题从根上解决了。

而且顺带**缓解了 P1-9 的越权风险**：userId 不再由调用方任意指定。

> 但这个改动**带出一个新风险**，见下一节。

---

## 三、🔴 新引入的风险：匿名用户会共用同一个记忆空间

改完之后，`userId` 完全来自 `X-User-Id` 头。而这个头在**没带**的时候会被兜底成 `anonymous`：

```java
// AiUserContextInterceptor
AiUserContext.set(userId == null || userId.isBlank() ? AiUserContext.ANONYMOUS : userId);
```

于是：

```
GET /chat/stream?sessionId=s1&message=我叫小明        # 不带 X-User-Id
→ userId = "anonymous"
→ memoryId = "assistant-demo::anonymous::s1"
→ 写入 "用户叫小明"
```

**再换一个人（也不带头）来问**：

```
GET /chat/stream?sessionId=s1&message=我是谁
→ userId = "anonymous"（同一个人！）
→ memoryId = "assistant-demo::anonymous::s1"（同一个会话！）
→ 助手答："你叫小明"
```

**所有未携带用户标识的请求会共享同一份会话历史和长期记忆。** 这与用户最初报的那个 bug
是同一族的另一种形态 —— 他遇到的是"换了 session 就不知道自己是谁"，这个是"别人知道我是谁"。

**触发条件是"网关没注入 `X-User-Id`"**（客户端直连、网关配置漏了、或者本地调试）。
一旦发生，**数据会被污染而且很难发现** —— 要等到有人抱怨"它答的这不是我"。

### 修法：`anonymous` 必须快速失败，不能静默放行

```java
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message) {

        // ⚠️ userId 必须来自网关注入的 X-User-Id。缺了就拒绝，不能退化成 anonymous ——
        //    anonymous 会让所有未认证请求共用同一份会话历史和长期记忆
        //    （A 说"我叫小明"，B 问"我是谁"会答"小明"），而且数据一旦污染很难发现。
        String userId = AiUserContext.get();
        if (userId == null || userId.isBlank() || AiUserContext.ANONYMOUS.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少用户标识（X-User-Id）。本服务不应直接暴露，请经网关访问");
        }
        ...
```

需要加两个 import：

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
```

### 为什么这里和 token 台账的处理不同

这是一个重要的区分，值得记住：

| 用途 | `anonymous` 该怎么办 | 理由 |
|---|---|---|
| **token 台账**（`TokenUsageListener`） | **兜底成 anonymous，照记** | 统计性质 —— 宁可记一笔"匿名用量"，也不要丢记录 |
| **记忆**（会话 + 长期） | **拒绝服务（401）** | 隔离性质 —— 宁可拒绝，也不能让两个人的数据串在一起 |

**同一个 `anonymous` 常量，在两个场景下的正确行为是相反的。** 这个区分不是吹毛求疵：
把两者都做成"兜底"就是现在这个隐私问题，都做成"拒绝"就会让用量统计丢数据。

---

## 四、四处清理

### 4.1 `TokenUsageListener.onResponse` 里有个没用的残留变量

```java
@Override
public void onResponse(ChatModelResponseContext responseContext) {
    TokenUsage tokenUsage = responseContext.chatResponse().tokenUsage();
    if(tokenUsage == null){
        return;
    }
    String userId = AiUserContext.get();        // ← ★ 这行没用
    recorder.record(new TokenUsageRecord(
            userIdOf(responseContext.attributes()),   // ← 实际用的是这个
            ...
```

**`userId` 这个局部变量从头到尾没被使用**（实际用的是 `userIdOf(attributes)`）。

它无害，但**有误导性**：后来人读到这里会以为"onResponse 是通过 ThreadLocal 取 userId 的"，
而这正是我们要避免的写法 —— 在 I/O 线程上它永远是 null。**删掉它。**

### 4.2 `UserMemoryRecorder.embed(String)` 成了死方法

```java
private float[] embed(String text) {
    return embeddingModel.embed(text).content().vector();
}
```

改成 `batchEmbed(List<String>)` 之后，**没有任何地方再调用它**（grep 确认：
全项目只有 `MemoryRecallService` 在用 `embeddingModel.embed(...)`，那是另一个类）。
**删掉。**

> 这类"重构后留下的私有方法"IDE 一般会给灰字提示，但很容易被忽略 ——
> 它不影响编译，只是让读者多花时间确认"这个方法还有人用吗"。

### 4.3 `UserMemoryRecorder` 有两个多余 import

```java
import com.best.ai_common.register.EmbeddingModelRegister;   // 代码用的是 EmbeddingModel
import dev.langchain4j.data.message.UserMessage;             // 代码里没出现 UserMessage
```

### 4.4 `MemoryRecallService` 的 catch 还是没改

```java
} catch (Exception e){
    log.error("失败");        // ← 没传 e，堆栈全丢；文案无任何上下文
    return List.of();
}
```

这条从 17 号文档就挂着，还没落地。**它是你现在排查同类问题的最大障碍** ——
这次是好运（阈值问题，至少能从"结果为空"推断），下次如果是异常，你只会看到"失败"两个字。

完整改法见 `17-长期记忆不生效诊断.md` 第 5.1 节（那里给了整个文件的替换版）。

---

## 五、全链路 token 的真实覆盖情况

你提到"全链路的 token 检测" —— 我把所有会调模型的地方列出来，逐个核对：

| # | 调用点 | 走哪个 Model 实例 | 有 listener 吗 | userId 归属正确吗 |
|---|---|---|---|---|
| 1 | 主对话（同步 `ask()`） | `ChatModelRegister` | ✅ | ✅ 请求线程 |
| 2 | 主对话（流式 `chat()`） | `StreamingChatModelRegister` | ✅ | ✅ **靠你新加的 `onRequest` 快照** |
| 3 | 会话摘要生成 | `ChatModelRegister.getDefault()` | ✅ | ✅ 请求线程（`add()` 里同步调用） |
| 4 | 事实抽取 | `ChatModelRegister.get(extractProfile)` | ✅ | ✅ **靠你新加的 `AiUserContext.open`** |
| 5 | **向量化（写入）** | `EmbeddingModelRegister` | ❌ | — |
| 6 | **向量化（检索）** | `EmbeddingModelRegister` | ❌ | — |

**1–4 已经齐了**，而且 2 和 4 这两个最难的（跨线程）你都处理了。

**缺口是 5、6 —— embedding 的调用完全没有统计。**

### 要不要补这个缺口？我的判断是**一期不补**

三个理由：

1. **成本量级差得远。** embedding 的价格通常是 chat 的 1/10 ~ 1/100。
   你们主对话一轮可能烧几千 token，而检索一次 embedding 只处理几十个字。
2. **很多 provider 的 embedding 响应不返回 usage。** 拿不到 `tokenUsage` 时，
   你只能记"调用次数 + 耗时"，那和 token 台账的语义不一致（表里那几个 token 列会是 null）。
3. **成本不对等。** 要新增一个 `EmbeddingModelListener` 实现 + 改 `EmbeddingModelRegister`
   + 可能要给台账表加一列区分 `chat`/`embed`，换来的是一张几乎全是 0 或 null 的表。

**如果你要的是"发现异常"而不是"成本核算"**，更划算的做法是在
`EmbeddingModelRegister` 里包一层计时/计数（或者用 Micrometer 的 `Timer`），
**不需要走 token 台账那张表**。

### 如果确定要补，代码在这里

我确认过 API 都存在（`javap` 实测）：`EmbeddingModelListener` 有
`onRequest` / `onResponse` / `onError`（都是 default），`OpenAiEmbeddingModel.builder()`
有 `.listeners(List)`，`EmbeddingModelResponseContext` 有 `attributes()` 和 `textSegments()`。

```java
package com.best.ai_common.metrics;

import com.best.ai_common.context.AiUserContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Instant;
import java.util.Map;

/**
 * 向量化调用的用量统计。
 *
 * <p><b>和 ChatModelListener 是两套接口</b> —— 名字相似但类型完全不同，不能复用。
 *
 * <p><b>为什么同样要用 onRequest 快照：</b>
 * {@code embed(...)} 是同步的，onResponse 本来就在调用线程上、读得到 ThreadLocal。
 * 但 {@code embedAsync(...)} 是异步的，会换线程。统一用 onRequest 快照，
 * 既保持一致，也防将来有人换成 async 版本时静默失效。
 *
 * <p><b>注意 tokenUsage 大概率是 null</b> —— 不少 provider 的 embedding 响应不返回用量。
 * 拿不到时本类仍然记一笔（token 列为 null），这样至少"调用次数"是准的。
 */
public class EmbeddingUsageListener implements EmbeddingModelListener {

    private static final String ATTR_USER_ID = "ai.user.id";

    private final String serviceName;
    private final String profile;
    private final String modelName;
    private final TokenUsageRecorder recorder;

    public EmbeddingUsageListener(String serviceName, String profile,
                                  String modelName, TokenUsageRecorder recorder) {
        this.serviceName = serviceName;
        this.profile = profile;
        this.modelName = modelName;
        this.recorder = recorder;
    }

    @Override
    public void onRequest(EmbeddingModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        String userId = AiUserContext.get();
        // 同样必须兜底：ConcurrentHashMap 不接受 null value
        attributes.put(ATTR_USER_ID, userId == null || userId.isBlank() ? AiUserContext.ANONYMOUS : userId);
    }

    @Override
    public void onResponse(EmbeddingModelResponseContext responseContext) {
        // embedding 的 usage 大概率拿不到，拿不到就给 null —— 不要补 0，
        // 0 会被误读成"这次调用没花 token"，而事实是"不知道"
        TokenUsage usage = responseContext.embeddingResponse() == null
                ? null : null;   // ⚠️ 见下方说明：EmbeddingResponse 没有 tokenUsage 方法
        ...
    }
}
```

> ⚠️ **上面这段我没有写完整，因为有一处需要你先确认**：
> `EmbeddingResponse` 上到底有没有 `tokenUsage()` / `metadata()` —— 我在 `javap` 里
> 看到 `EmbeddingModelResponseContext` 同时暴露了 `embeddingResponse()`（新 API）和
> `response()`（旧 API `Response<List<Embedding>>`），两者取 usage 的方式不同。
>
> **建议你先跑一次真实调用**，在 `onResponse` 里把这两个对象 `toString()` 打出来，
> 看到底哪个有 usage。**如果都没有，就别做这个功能**（回到上面"一期不补"的判断）。

---

## 六、完整代码（三处必改）

### 6.1 `ChatStreamController`（加匿名拦截）

```java
package com.best.assistant_demo.controller;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.assistant_demo.assistant.ChatAssistant;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatStreamController {

    private final ChatAssistant assistant;

    private final UserMemoryRecorder userMemoryRecorder;

    @Value("${ai.memory.assistant-id:${spring.application.name:unknown}}")
    private String assistantId;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message) {

        // ⚠️ userId 必须来自网关注入的 X-User-Id。
        //
        // 缺了就【拒绝】，不能退化成 anonymous —— 因为 memoryId 里带 userId，
        // 一旦退化成 anonymous，所有未携带标识的请求会共用同一份会话历史和长期记忆：
        // A 说"我叫小明"，B 问"我是谁"会答"小明"。而且数据污染后很难发现。
        //
        // 注意这和 token 台账的处理是【相反】的：那边 anonymous 要照记（统计不能丢），
        // 这边必须拒绝（隔离不能串）。
        String userId = AiUserContext.get();
        if (userId == null || userId.isBlank() || AiUserContext.ANONYMOUS.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少用户标识（X-User-Id）。本服务不应直接暴露，请经网关访问");
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean finished = new AtomicBoolean(false);

        String memoryId = SessionKey.of(assistantId, userId, sessionId).toMemoryId();

        assistant.chat(memoryId, message)
                .onPartialResponse(token -> {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        emitter.send(token);
                    } catch (IOException | IllegalStateException e) {
                        // 客户端断开（关页面、切网络）会走到这里。这不是错误，是常态。
                        log.warn("推送 token 失败，客户端可能已断开: {}", e.toString());
                        finished.set(true);
                    }
                })
                .onCompleteResponse(response -> {
                    // 长期记忆的抽取触发点：异步，不阻塞这次响应。
                    // 里面会 AiUserContext.open(userId) 把上下文带进异步线程。
                    userMemoryRecorder.recordAsync(
                            memoryId,
                            List.of(UserMessage.from(message),
                                    AiMessage.from(response.aiMessage().text()))
                    );
                    if (finished.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                })
                .onError(error -> {
                    // ⚠️ 必须打日志。流式的错误默认对用户表现为"什么都没发生"。
                    log.error("流式对话失败, memoryId={}", memoryId, error);
                    if (finished.compareAndSet(false, true)) {
                        emitter.completeWithError(error);
                    }
                })
                // 少了这一行，上面所有回调都不会执行 —— TokenStream 是惰性的
                .start();

        return emitter;
    }
}
```

### 6.2 `TokenUsageListener`（删残留变量）

只贴变化的方法，其余不动：

```java
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        TokenUsage tokenUsage = responseContext.chatResponse().tokenUsage();
        if (tokenUsage == null) {
            // 服务端没返回 usage。不要在这里补 0 —— 0 会被当成"这次调用没花 token"，
            // 而事实是"不知道"。留空才有机会被发现。
            return;
        }
        // 注意：这里【不能】用 AiUserContext.get() —— onResponse 的流式回调跑在
        // 模型 I/O 线程上，ThreadLocal 读出来是 null。userId 只在 onRequest 里
        // 快照进 attributes，这里从 attributes 取。
        recorder.record(new TokenUsageRecord(
                userIdOf(responseContext.attributes()),
                serviceName,
                profile,
                modelName,
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount(),
                Instant.now()
        ));
    }
```

### 6.3 `UserMemoryRecorder`（删死方法与多余 import）

只贴 import 区内需要删的部分和方法：

```java
// 删掉这两个没用到的 import：
// import com.best.ai_common.register.EmbeddingModelRegister;
// import dev.langchain4j.data.message.UserMessage;

// 删掉这个没人调用的私有方法（已改用 batchEmbed）：
// private float[] embed(String text) {
//     return embeddingModel.embed(text).content().vector();
// }
```

---

## 七、验证

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

**匿名拦截的验证**（改完 `ChatStreamController` 后）：

```bash
# ① 不带 X-User-Id → 应该 401，而不是"能回答"
curl -i "http://localhost:8080/chat/stream?sessionId=s1&message=我叫小明"

# ② 带上 → 正常
curl -N "http://localhost:8080/chat/stream?sessionId=s1&message=我叫小明" -H "X-User-Id: u1"

# ③ 换个人问同一个 sessionId → 应该【不知道】小明（memoryId 里的 userId 不同）
curl -N "http://localhost:8080/chat/stream?sessionId=s1&message=我是谁" -H "X-User-Id: u2"
```

**第 ③ 条是这次最该验的** —— 它同时验证了"会话隔离"和"匿名不会串数据"。

**抽取的 token 归属验证**（你 2.2 那个改动的效果）：

```sql
-- 跑一轮带 X-User-Id 的对话，等抽取完成（异步，几秒后），然后查：
SELECT user_id, profile, model_name, input_tokens, output_tokens
FROM ai_token_usage ORDER BY id DESC LIMIT 10;
```

**应该能看到两条记录**：一条 `profile=free` 的主对话，
一条 `profile=free`（或你配的 `extract-profile`）的抽取调用 ——
**两条的 `user_id` 都应该是 `u1`，不是 `anonymous`**。

如果抽取那条是 `anonymous`，说明 `AiUserContext.open` 没生效，检查 `recordAsync` 的 try 块。

---

## 一页速记

```
✅ 你改对的（3 处）
  onRequest 的 null 兜底       ← 这是我漏的真 bug：ConcurrentHashMap 不接受 null value，
                                  而它在框架的 chat() 里抛 NPE，会让整个模型调用失败
  AiUserContext.open 包异步任务 ← 写法正确（try-with-resources 保证异常路径也恢复）；
                                  修好了抽取的 token 归属
  Controller 改用 AiUserContext ← 会话/长期/台账三处同源

🔴 新风险（建议优先修）
  匿名用户共用同一份记忆空间：不带 X-User-Id → userId="anonymous" →
  memoryId="assistant-demo::anonymous::<sessionId>" → A 说"我叫小明"，B 问"我是谁"会答"小明"
  修法：userId 为 null/blank/anonymous 时抛 401（快速失败，别静默放行）
  关键区分：token 台账 anonymous 要【照记】（统计不能丢）；
            记忆 anonymous 要【拒绝】（隔离不能串）—— 同一个常量，相反的处理

🟡 清理（4 处）
  TokenUsageListener.onResponse 里没用的局部变量 String userId = AiUserContext.get()
  UserMemoryRecorder.embed(String) 死方法
  UserMemoryRecorder 两个多余 import（EmbeddingModelRegister / UserMessage）
  MemoryRecallService 的 log.error("失败")（17 号就提了，还没改）

⚪ 全链路 token 覆盖
  1 主对话同步  ✅   2 主对话流式 ✅   3 摘要 ✅   4 事实抽取 ✅（你新修的）
  5 向量化写入  ❌   6 向量化检索 ❌  ← 建议【一期不补】
  理由：cost 量级差 10~100 倍；provider 多半不返回 embedding usage；
        要新增一个 listener + 改 register + 可能改表结构，性价比低
        如果目的是「发现异常」而非「成本核算」，用 Micrometer Timer 更划算
```