# 47 · 第 1 章 · HarnessAgent 生命周期与第一次调用（Spring Boot + 注册表版）

> **本章吃透一个功能：HarnessAgent 的生命周期。**
> Builder 提成 Spring `@Bean`（应用启动时配一次）→ 每请求 `build()` 一个实例 → `call()` → `close()`。
> 章末用两个 curl 证明：**新实例 ≠ 新会话**。
>
> 对应官方文档页：quickstart / building-blocks/agent / harness/architecture
> 前置：46 号总纲的骨架已敲完（含模型注册表），`AI_API_KEY` 已配，服务能起来
>
> ⚠️ **2026-10-16 修订**：模型从「starter 自动装配的 bean」改为「注册表按名引用」。
> `AgentLabConfig` 的 `model(Model)` 参数注入改为 `.model("fast")` + 注入 `ModelRegistrar` 保序。已敲过本章的话，按 §2 替换这一个文件即可。

---

## 1. 你会敲出什么

| 文件 | 动作 |
|---|---|
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch01_lifecycle/AgentLabConfig.java` | 新建（已敲过则**替换**） |
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch01_lifecycle/ChatController.java` | 新建 |

骨架（两个 pom / yml / 四个骨架类 / 启动类）在 46 号，本章不碰。`ChatController` 本次修订**无变化**。

---

## 2. AgentLabConfig 完整代码

**文件路径：`/Users/best/project/ai_platform/ai-agentscope/src/main/java/com/best/ai_agentscope/ch01_lifecycle/AgentLabConfig.java`**

```java
package com.best.ai_agentscope.ch01_lifecycle;

import com.best.ai_agentscope.model.ModelRegistrar;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第 1 章：把 HarnessAgent.Builder 提成 Spring Bean。
 *
 * Builder 是「配置模板」，应用启动时配一次，整个进程共享；
 * 会话身份不放这里 —— 身份每次调用走 RuntimeContext（见 ChatController）。
 */
@Configuration
public class AgentLabConfig {

    /**
     * 注入 ModelRegistrar 只为保序：.model("fast") 在 builder 配置时【立即解析】，
     * 注册必须先于本 bean 创建 —— 构造参数注入即顺序依赖（类型安全，改名不会静默断）。
     */
    @Bean
    public HarnessAgent.Builder noteTakerBuilder(ModelRegistrar registrar) {
        return HarnessAgent.builder()
                .name("note-taker")
                .agentId("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model("fast")
                .workspace(Path.of(".agentscope/workspace"));
    }
}
```

**逐行讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| `@Configuration` + `@Bean` | Builder 是无状态配置模板，提成单例 bean 正是 Spring 的舒适区。官方推荐的「启动时配一次、全进程共享」在 Spring 里的标准形态就是这样 |
| `noteTakerBuilder(ModelRegistrar registrar)` | 参数**只为保序**（IDE 可能提示"未使用"，**别删**）：`.model("fast")` 在 builder 配置时立即解析，注册必须先完成。构造参数注入即顺序依赖，比 `@DependsOn("bean名")` 类型安全——改 bean 名不会静默断 |
| `.model("fast")` | **按名引用注册表里的模型**。生成参数的默认值在 `ModelProperties.ModelSpec` 的字段上、yml 只写覆盖项（46 号 §6/§7），都不在这里写——**这里只声明「用哪个模型」**。换成 `.model("strong")` 就切另一个模型，Java 代码只改这一个词 |
| `.name("note-taker")` + `.agentId("note-taker")` | `name` 是给人看的标识；`agentId` 是**会话状态命名空间的一部分**。本章两个请求靠同一个 `agentId` 才认得出彼此 |
| `.sysPrompt(...)` | 稳定指令，进 System。中文直接写——父 pom 配了 UTF-8 |
| `.workspace(Path.of(".agentscope/workspace"))` | 工作区 = Agent 的「家」：人格文件 / 记忆 / 会话日志都落这里。相对路径跟着 `spring-boot:run` 的工作目录（模块目录）走 |

⚠️ **Builder bean 里不能放请求状态**。它是单例，所有请求复用；`userId` / `sessionId` 这种每次调用都不同的东西只能走 `RuntimeContext`。这是本章最重要的一条边界。

---

## 3. ChatController 完整代码

**文件路径：`/Users/best/project/ai_platform/ai-agentscope/src/main/java/com/best/ai_agentscope/ch01_lifecycle/ChatController.java`**

```java
package com.best.ai_agentscope.ch01_lifecycle;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 1 章：第一个 Agent 端点。
 *
 * 形态 = 官方推荐的「共享 Builder，每请求 build() 一个实例，用完即关」。
 * 两个 curl 打同一个 sessionId，第二个请求能答出第一个请求里的信息。
 */
@RestController
public class ChatController {

    private final HarnessAgent.Builder agentBuilder;

    // Builder 由 Spring 注入（AgentLabConfig 里那个单例）
    public ChatController(HarnessAgent.Builder agentBuilder) {
        this.agentBuilder = agentBuilder;
    }

    @PostMapping("/chat")
    public String chat(@RequestParam String userId,
                       @RequestParam String sessionId,
                       @RequestParam String message) {
        // 每请求一个新实例；try-with-resources 保证 call 结束（或抛异常）后 close()
        try (HarnessAgent agent = agentBuilder.build()) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build();
            // call 返回 Mono<Msg>——惰性，.block() 订阅并等待。
            // MVC 一个请求一条线程，在这里阻塞是正当的（第 3 章的 SSE 端点才不阻塞）
            Msg reply = agent.call(new UserMessage(message), ctx).block();
            return reply.getTextContent();
        }
    }
}
```

**逐段讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| 构造器注入 `HarnessAgent.Builder` | 控制器不持有 Agent 实例，只持有模板。**实例的生命周期 = 一次请求**：build → call → close |
| `try (HarnessAgent agent = ...)` | `HarnessAgent` 实现了 `AutoCloseable`，进 try-with-resources。请求结束或抛异常都会 close，不会泄漏执行门和缓存 |
| `@RequestParam userId / sessionId` | **身份从请求来**。⚠️ 生产环境这个值必须来自认证上下文（网关校验过的头），**绝不能信任客户端随便传的**——这是项目 19 号交付单的教训，本章先照着学习舱的写法跑，第 8 章多租户时再收紧 |
| `agent.call(msg, ctx)` 返回 `Mono<Msg>` | 一次调用内部可以多轮推理 + 多次工具，最终收敛成一条回复。本章只有一轮对话，看不到多轮，第 4 章接工具后就有了 |
| `.block()` | **订阅并等待**。Reactor 的 `Mono` 是惰的：不订阅，什么都不发生——连 API 调用都不会发 |
| `reply.getTextContent()` | 回复是 `Msg`（结构化消息），`.getTextContent()` 取纯文本。第 3 章会看到它还有工具调用、思考等内容块 |

---

## 4. 三个必须建立的心智模型

**① Builder / 实例 / 会话是三个维度，不是一回事。**

- Builder = 配置模板（Spring 单例 bean，启动时配一次）
- 实例 = 执行引擎（每请求 build() 一个，close() 释放）
- 会话 = 状态（由 `agentId + userId + sessionId` 寻址，持久在 Session Log 里）

本章两个请求共享同一个 Builder、同一个会话身份。实例关闭不影响会话——下一个请求再 build() 一个，接着聊。

**② 会话连续性不靠内存，靠日志。**

`HarnessAgent` 默认把每次执行写进 Session Log（落在 workspace 解析出的根目录下 `.agentscope-runtime/`）。第二个请求的新实例启动时按身份三元组把日志读回来，所以它「记得」第一个请求。**重启服务也不丢**——只要 workspace 目录还在。

**③ `RuntimeContext` 是 per-call 的，不是 per-实例的。**

同一个 Builder build 出来的实例可以服务不同用户的不同会话——每次 call 传不同的 ctx 就行。⚠️ 但也因此：**ctx 传错了不会报错，只会静默读写别人的会话**。第 8 章（多租户隔离）专门收这个账。

---

## 5. 运行

```bash
cd /Users/best/project/ai_platform
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
export AI_API_KEY=你的端点密钥

/opt/homebrew/bin/mvn -pl ai-agentscope spring-boot:run
```

看到 `Started AgentscopeLabApplication in x.xxx seconds` 后，**另开一个终端**：

```bash
# 第一轮：告诉它一件自己的事
curl -X POST "http://localhost:8081/chat" \
  -d "userId=alice" -d "sessionId=demo-session" \
  -d "message=我叫天宇，今天准备一个技术分享。"

# 第二轮：全新实例（服务内每次请求都新建），同一会话
curl -X POST "http://localhost:8081/chat" \
  -d "userId=alice" -d "sessionId=demo-session" \
  -d "message=我叫什么？今天要干什么？"
```

**预期**：第二轮的响应包含「天宇」和「技术分享」两个信息。第一轮的响应内容随意——我们只用它「写入会话」。

---

## 6. 检查点

```bash
# 会话日志真的落盘了（在模块目录下找）
find ai-agentscope -name ".agentscope-runtime" -type d
```

能看到目录且里面有文件。**看不到 = 连续性是无源之水**，先解决这个再往下。

---

## 7. 自查清单（6 个实验，做完再进第 2 章）

| # | 实验 | 预期 | 证明什么 |
|---|---|---|---|
| 1 | 上面两个 curl 原样跑 | 第二轮答出「天宇」+「技术分享」 | 会话连续性成立 |
| 2 | 第二轮把 `sessionId` 改成 `other-session` | **答不出**你是谁 | 身份三元组是槽位键，不是建议值 |
| 3 | 跑完实验 1，**停服务**，删掉 `.agentscope-runtime`，重启再跑原样的两个 curl | 第二轮失忆 | 状态在日志里，不在内存里；重启不丢、删日志才丢 |
| 4 | 第二轮把 `userId` 改成 `bob`（sessionId 不变） | 答不出 | userId 也是槽位键的一部分 |
| 5 | 停服务，启动时显式把密钥置空：`mvn -pl ai-agentscope spring-boot:run -Dspring-boot.run.arguments="--ai.models.fast.api-key="` | **启动就失败**，报 `模型 fast 缺少 api-key（配 ai.models.fast.api-key）` | 配置缺失要早炸、说人话。⚠️ 别用 `unset`/`env -u` 做这个实验——**本机实测发现你的 shell 里本来就 export 了 AI_API_KEY，而且摘掉后应用照样起来了**（另有注入源）；命令行显式覆盖才是确定性的做法 |
| 6 | 把代码里的 `.block()` 改成 `agent.call(...)` 直接返回 | 编译不过（返回类型不匹配）→ 改回 | `Mono` 惰性在 MVC 里的表现：**必须订阅**；第 3 章 SSE 端点会展示「不 block 的流式」 |

实验 2/3/4 是**唯一能发现静默失效**的三个——「看着跑通了但其实没连续性」这种事，只有主动制造失忆才能暴露。

---

## 8. 容易写错 / 被改坏的 5 个点

1. **把请求状态塞进 Builder bean**——Builder 是单例，`userId`/`sessionId` 放进去 = 所有请求共用最后一次的值，且不报错。身份只走 `RuntimeContext`。
2. **忘记 `.block()`**——`Mono` 惰性，不订阅静默什么都不做。和项目里 `TokenStream` 必须 `.start()` 是同一类错误：响应式对象的「构造」不等于「执行」。
3. **`.model("fast")` 的解析时机**——它是**立即解析**（builder 配置方法调用时就查注册表，实测），不是等到 `build()`。所以注册必须先完成：`noteTakerBuilder` 的 `ModelRegistrar` 参数**不能删**（哪怕编译器提示"未使用"）——删了就变成 bean 创建顺序不定，会随机抛 `Cannot resolve model: "fast"`。
4. **workspace 相对路径 + 换启动方式**——`.agentscope/workspace` 跟着工作目录跑。`spring-boot:run` 在模块目录，打成 jar 后 `java -jar` 在哪儿跑就落哪儿，表现为「时不时失忆」。想固定就写绝对路径。
5. **userId 信任客户端传参**——本章为了方便实验做成了请求参数。生产必须来自认证上下文，第 8 章收紧。**现在就要知道这是临时形态**，别顺手抄进真实项目。

---

## 9. 下一篇预告

**48 · 第 2 章 · 模型层**——注册表怎么支撑多模型；`maxRetries` / `fallbackModel("strong")` / `failoverListener` 容错三件套；模型能力探针；以及本章埋的伏笔：**你的端点背后是哪家模型**，第 2 章实测见分晓。
