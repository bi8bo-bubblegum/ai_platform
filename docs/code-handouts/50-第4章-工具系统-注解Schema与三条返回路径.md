# 50 · 第 4 章 · 工具系统：注解、Schema 与三条返回路径

> **本章吃透一个功能：AgentScope 的工具系统。**
> 一个普通 Java 类加 `@Tool` 注解就能变成模型可调用的工具；本章把「注解 → Schema → 调用 → 返回」整条链跑通，
> 并实测三条返回路径（成功 / 异常 / 挂起）。
>
> 对应官方文档页：building-blocks/tool
> 前置：46 号骨架 + 47 号第 1 章已跑通；本章新增一个 `Toolkit` bean

---

## 1. 你会敲出什么

| 文件 | 动作 |
|---|---|
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/LabTools.java` | 新建 |
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/ToolkitConfig.java` | 新建 |
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/ToolProbeController.java` | 新建 |
| `probe/ToolProbe.java`（可选，§12） | 新建，`exec:java` 直接跑，不用起服务 |

本章**不碰** agent builder——工具先单独验通，第 5 章讲完权限，第 6 章才把 toolkit 挂到 `HarnessAgent` 上。

---

## 2. 注解驱动：`@Tool` 的全部属性

`io.agentscope.core.tool.Tool` **本身是个注解接口**（`extends java.lang.annotation.Annotation`，javap 实测），全部属性：

| 属性 | 默认 | 作用 |
|---|---|---|
| `name` | — | 工具名，模型就看到这个字符串 |
| `description` | — | **必填**（构造器 `requireNonNull`），写不清模型就不会用 |
| `strict` | `false` | 对应 OpenAI 的 structured outputs strict 模式 |
| `readOnly` | `false` | 只读工具——**第 5 章权限判定的关键输入** |
| `concurrencySafe` | `false` | 能否并发调用（见 §6） |
| `externalTool` | `false` | 外部工具（MCP），不本地执行 |
| `stateInjected` | `false` | 是否需要注入会话状态 |
| `returnDirect` | `false` | 结果直接回给用户，不再过模型 |
| `dangerousFiles` | `{}` | 危险文件清单（权限系统用） |
| `dangerousDirectories` | `{}` | 危险目录清单（权限系统用） |
| `converter` | `DefaultToolResultConverter` | 返回值 → `ToolResultBlock` 的转换器 |

`@ToolParam`（标在**方法参数**上）只有三个属性：`name` / `required` / `description`。

**关键认知**：工具不需要实现任何接口。`Toolkit.registerTool(new LabTools())` 会反射扫描这个类里所有带 `@Tool` 的 public 方法，逐个包成 `ReflectiveFunctionTool`（extends `ToolBase` implements `AgentTool`）。

---

## 3. Toolkit 的三种注册方式

```java
Toolkit toolkit = new Toolkit();          // ① 默认构造
toolkit.registerTool(new LabTools());     // ② 注解扫描（本章用这条）
```

| 方式 | 写法 | 用途 |
|---|---|---|
| 注解扫描 | `registerTool(Object)` | 普通 Java 方法，最常用 |
| 显式实现 | `registerAgentTool(AgentTool)` | 需要完全控制 schema / 异步逻辑 |
| MCP | `registration().mcpClient(wrapper).apply()` | 外部 MCP server（见 §7） |

还有一条 fluent 路子（一次注册多个源）：

```java
toolkit.registration()
        .tool(new LabTools())              // 注解扫描
        .enableTools(List.of("get_weather"))   // 只开这些
        .disableTools(List.of("boom"))         // 关掉这些
        .group("lab")                           // 工具组（按需披露，第 14 章讲）
        .apply();
```

`registration()` 的方法链：`tool()` / `agentTool()` / `mcpClient()` / `subAgent()` / `enableTools()` / `disableTools()` / `mcpToolNamePrefix()` / `group()` / `presetParameters()` / `propagateMeta()` / `extendedModel()` → `apply()`。

---

## 4. 实测：生成的 Schema 长什么样

模型看到的就是 `getToolSchemas()` 的输出。实测（§12 探针 ②）：

```json
[ {
  "name" : "get_weather",
  "description" : "查询指定城市的当前天气",
  "parameters" : {
    "type" : "object",
    "properties" : {
      "city" : { "type" : "string", "description" : "城市名，如 北京" }
    },
    "required" : [ "city" ]
  },
  "outputSchema" : null,
  "strict" : null
}, ... ]
```

三个要点：

1. **`required` 来自 `@ToolParam(required = true)`**——漏写 `required` 模型就可能不传该参数，然后你的方法收到 `null`。
2. **`outputSchema` 默认 `null`**——返回值结构没有约束。要约束就得自己实现 `getOutputSchema()`。
3. **`strict` 默认 `null`**（注解不设时）。要开 strict 在 `@Tool(strict = true)` 里设。

> ⚠️ **第 2 章埋的雷在这里拆**：OpenAI 兼容端点上「工具 + 结构化输出」可能冲突（端点优先遵循 `response_format` 而跳过工具）。`strict=true` 会把这个冲突放大。**建议 `strict` 保持不设**，除非你的端点实测支持。

---

## 5. 实测：三条返回路径

`Toolkit.callTool(ToolCallParam)` 返回 `Mono<ToolResultBlock>`。实测输出：

| 场景 | `getState()` | `isSuspended()` | `getMetadata()` | 文本 |
|---|---|---|---|---|
| 正常返回 | `RUNNING` | `false` | `{}` | `"北京：晴，26℃"` |
| 工具抛异常 | `ERROR` | `false` | `{}` | `Error: Tool execution failed: 工具内部炸了` |
| 抛 `ToolSuspendException` | `RUNNING` | **`true`** | **`{agentscope_suspended=true}`** | `等人确认` |

**三个必须记住的事实**：

1. **成功路径的 state 是 `RUNNING`，不是 `SUCCESS`**。别写 `if (state == SUCCESS)` 当判据——直调时它就不成立。`ToolResultState` 有五态：`SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING`，本章实测拿到的是 `RUNNING`。
2. **异常被框架接住了**，不会往上传。模型收到的是 `Error: Tool execution failed: <原因>` 文本——这是好事（模型能自己纠正重试），但也意味着**你的异常栈不会出现在日志里**，要排查只能自己打日志。
3. **挂起是一个显式状态**：`ToolSuspendException` + `isSuspended()` + `metadata` 里的 `agentscope_suspended`。这是第 5 章 HITL 的底层载体。

---

## 6. 执行配置与并发

`Toolkit.callTools(blocks, ExecutionConfig, Agent, RuntimeContext)` 是批量入口。`ExecutionConfig` 六个字段：

| 字段 | 作用 |
|---|---|
| `timeout` | 单次调用超时 |
| `maxAttempts` | 重试次数 |
| `initialBackoff` / `maxBackoff` / `backoffMultiplier` | 指数退避三参数 |
| `retryOn` | 什么异常才重试（`Predicate<Throwable>`） |

框架给了两个常量：`ExecutionConfig.MODEL_DEFAULTS` 和 `ExecutionConfig.TOOL_DEFAULTS`——**直接用后者当起点**，别从零配：

```java
ExecutionConfig cfg = ExecutionConfig.TOOL_DEFAULTS;   // 或 builder().timeout(...).build()
```

⚠️ **`ExecutionConfig` 里没有「并发度」配置**（javap 实测：只有上面六个字段）。并发由 **`@Tool(concurrencySafe = true)`** 声明：同一个模型轮次里返回多个 tool_call 时，框架只把标记为并发安全的那些并行跑，其余串行。

**这条设计的含义**：`concurrencySafe` 不是性能开关，是**正确性声明**。你的工具有共享可变状态（写文件、改缓存）就必须留 `false`，否则两个请求同时改同一份数据。

---

## 7. MCP 接入（本章只讲形态，不接真 server）

```java
toolkit.registration()
        .mcpClient(mcpClientWrapper)      // 来自 McpClientBuilder
        .mcpToolNamePrefix("gh_")          // 防止与本地工具重名
        .apply();
```

底层入口是 `Toolkit.registerMcpClient(McpClientWrapper)`（返回 `Mono<Void>`，因为要等 MCP 握手 + 拉工具清单）。`McpClientBuilder` 支持四种传输：`StdioTransportConfig` / `SseTransportConfig` / `StreamableHttpTransportConfig` / `HttpTransportConfig`。

**本章不接真 MCP server**——理由：一是需要额外起一个 server，二是第 13 章（子 Agent）、第 14 章（Skill）还会再碰它。这里只要知道「MCP 工具注册后和本地工具走完全同一条调用链」（都变成 `AgentTool`，都过权限系统）。

---

## 8. Spring 形态代码

### 8.1 LabTools.java

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/LabTools.java`**

```java
package com.best.ai_agentscope.ch04_tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 第 4 章的工具类：纯注解，零接口实现。
 *
 * 注意三个工具分别对应第 5 章的三种权限命运：
 * get_weather 非只读、echo 只读、boom 危险。
 */
public class LabTools {

    @Tool(name = "get_weather", description = "查询指定城市的当前天气")
    public String getWeather(
            @ToolParam(name = "city", required = true, description = "城市名，如 北京") String city) {
        return city + "：晴，26℃";
    }

    @Tool(name = "echo", description = "只读回显", readOnly = true, concurrencySafe = true)
    public String echo(
            @ToolParam(name = "text", required = true, description = "要回显的文本") String text) {
        return text;
    }

    @Tool(name = "boom", description = "必然抛异常的工具，验证错误怎么回给模型")
    public String boom() {
        throw new IllegalStateException("工具内部炸了");
    }
}
```

**逐行讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| 类不用 `@Component` | 它是**被扫描的素材**，不是 bean。`ToolkitConfig` 里 `new LabTools()` 显式传入；注册进 Toolkit 后框架自己管 |
| `echo` 标 `readOnly = true, concurrencySafe = true` | 给第 5 章留探针：只读工具在 EXPLORE 模式下会被放行（见 51 号矩阵） |
| `boom` 故意抛异常 | 验证「异常被框架接住」这条路径——没有它你永远不知道工具失败时长什么样 |
| 方法全是 `public` 且返回 `String` | 反射扫描的硬要求；返回值被 `DefaultToolResultConverter` 包成 `ToolResultBlock` |

### 8.2 ToolkitConfig.java

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/ToolkitConfig.java`**

```java
package com.best.ai_agentscope.ch04_tool;

import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 第 4 章：Toolkit 提成 @Bean。启动时注册一次，全进程共享 */
@Configuration
public class ToolkitConfig {

    @Bean
    public Toolkit labToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LabTools());
        return toolkit;
    }
}
```

**为什么 Toolkit 是单例 bean**：它是「工具名 → AgentTool」的注册表，天然无状态（工具本身无状态）。每请求新建一个反而丢注册结果。

### 8.3 ToolProbeController.java

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/ch04_tool/ToolProbeController.java`**

```java
package com.best.ai_agentscope.ch04_tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class ToolProbeController {

    private final Toolkit toolkit;
    private final ObjectMapper objectMapper;

    public ToolProbeController(Toolkit toolkit, ObjectMapper objectMapper) {
        this.toolkit = toolkit;
        this.objectMapper = objectMapper;
    }

    /** 模型看到的就是这个 */
    @GetMapping("/tool/schemas")
    public Object schemas() {
        return toolkit.getToolSchemas();
    }

    /** 手动触发一次工具调用；json 是「模型侧原始参数」 */
    @PostMapping("/tool/call")
    public Map<String, Object> call(@RequestParam String name, @RequestParam String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = objectMapper.readValue(json, Map.class);
        ToolResultBlock r = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id(UUID.randomUUID().toString())
                        .name(name)
                        .input(input)
                        .content(json)      // ← 参数校验读的是它，不是 input
                        .build())
                .build()).block();
        StringBuilder text = new StringBuilder();
        for (ContentBlock b : r.getOutput()) {
            if (b instanceof TextBlock tb) {
                text.append(tb.getText());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", r.getState().name());
        out.put("isSuspended", r.isSuspended());
        out.put("metadata", r.getMetadata());
        out.put("text", text.toString());
        return out;
    }
}
```

**逐行讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| `.content(json)` 与 `.input(input)` **同时给** | ⚠️ **这是本章最大的坑**：参数 schema 校验读的是 `content`（模型侧原始 JSON 字符串），不是 `input` map。只给 `input` 会报 `未找到所需属性"city"`（实测）。真实链路里框架从模型响应里两个都填好，**只有手动构造时才踩** |
| `UUID.randomUUID()` 当 toolCall id | 真实链路里 id 由模型分配；手动调随便给，但要唯一（结果块要按 id 对应） |
| `.block()` | `Mono` 惰性；和第 1 章 `call(...).block()` 同一个道理 |
| `objectMapper` 构造注入 | Boot 自动装配的 `ObjectMapper` bean，别自己 new |
| 返回值只取 `TextBlock` | `ToolResultBlock.getOutput()` 是 `List<ContentBlock>`，可能含图片等多模态块；本章只处理文本 |

---

## 9. 运行与检查点

```bash
mvn -pl ai-agentscope spring-boot:run

# ① 看模型视角的 schema
curl -s localhost:8081/tool/schemas | python3 -m json.tool

# ② 正常路径
curl -s -X POST "localhost:8081/tool/call?name=get_weather" --data-urlencode 'json={"city":"北京"}'
#   期望 state=RUNNING, text="北京：晴，26℃"

# ③ 异常路径
curl -s -X POST "localhost:8081/tool/call?name=boom" --data-urlencode 'json={}'
#   期望 state=ERROR, text="Error: Tool execution failed: 工具内部炸了"
```

**检查点（硬判据）**：

| # | 操作 | 期望 |
|---|---|---|
| 1 | `①` 返回里有 3 个工具 | `get_weather` / `echo` / `boom` 三个 name |
| 2 | `②` 的 `state` | `RUNNING`（**不是** `SUCCESS`） |
| 3 | `③` 的 `state` | `ERROR`，且 text 以 `Error: Tool execution failed:` 开头 |
| 4 | `②` 故意把 `json` 改成 `{"city":"上海"}` 但 `input` 那边不变 | 本章 controller 两个都从同一个 json 来，所以结果一致——**这条是让你确认自己理解了两者关系** |
| 5 | 给 `LabTools` 加一个不带 `description` 的 `@Tool` 方法再启动 | 启动/注册期报错（`description` 是 `requireNonNull` 的） |

---

## 10. 容易写错 / 被改坏的 6 个点

1. **只给 `input` 不给 `content`** —— 参数校验读 `content`，症状是 `未找到所需属性"xxx"`，而你看代码明明传了。手动构造 `ToolUseBlock` 必踩。
2. **拿 `state == SUCCESS` 当成功判据** —— 实测拿到的是 `RUNNING`。要么看 `isSuspended()` + text，要么按业务判。
3. **工具方法抛异常以为会冒泡** —— 不会，框架接住转成 `Error: Tool execution failed: ...` 文本回给模型。你要在工具内部自己 `log.error`，否则线上排障抓瞎。
4. **`concurrencySafe = true` 当性能优化用** —— 它是**正确性声明**。有共享可变状态就留 `false`。
5. **`strict = true` 随手开** —— 兼容端点上会和工具调用冲突（第 2 章的实测记录表）。先确认端点支持。
6. **`@ToolParam` 漏写 `required`** —— schema 里 `required` 数组就缺该项，模型可能不传，你的方法拿到 `null`。

---

## 11. 自查清单

- [ ] `/tool/schemas` 返回 3 个工具，`get_weather` 的 `parameters.required` 是 `["city"]`
- [ ] 正常路径 `state=RUNNING`，text 是 `北京：晴，26℃`
- [ ] 异常路径 `state=ERROR`，text 含 `Error: Tool execution failed: 工具内部炸了`
- [ ] 把 `LabTools.getWeather` 的返回值改成抛异常，`②` 的表现和 `③` 一致
- [ ] 给 `echo` 加一个 `@ToolParam(required = false)` 的可选参数，`/tool/schemas` 里 `required` 不再含它
- [ ] 确认 `Toolkit` 是单例 bean（`/tool/schemas` 多次调用结果稳定）

---

## 12. 探针代码（可选，不起服务）

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/probe/ToolProbe.java`**

```java
package com.best.ai_agentscope.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;

public class ToolProbe {

    public static class LabTools {

        @Tool(name = "get_weather", description = "查询指定城市的当前天气")
        public String getWeather(
                @ToolParam(name = "city", required = true, description = "城市名，如 北京") String city) {
            return city + "：晴，26℃";
        }

        @Tool(name = "boom", description = "必然抛异常的工具")
        public String boom() {
            throw new IllegalStateException("工具内部炸了");
        }

        @Tool(name = "suspend_me", description = "主动挂起的工具")
        public String suspendMe() {
            throw new ToolSuspendException("等人确认");
        }
    }

    private static String textOf(ToolResultBlock r) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.getOutput()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LabTools());

        System.out.println("工具名: " + toolkit.getToolNames());
        System.out.println("Schema:\n"
                + om.writerWithDefaultPrettyPrinter().writeValueAsString(toolkit.getToolSchemas()));

        ToolResultBlock ok = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("c1").name("get_weather")
                        .input(Map.of("city", "北京")).content("{\"city\":\"北京\"}").build())
                .build()).block();
        System.out.println("成功: state=" + ok.getState() + " | text=" + textOf(ok));

        ToolResultBlock err = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("c2").name("boom")
                        .input(Map.of()).content("{}").build())
                .build()).block();
        System.out.println("异常: state=" + err.getState() + " | text=" + textOf(err));

        ToolResultBlock sus = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("c3").name("suspend_me")
                        .input(Map.of()).content("{}").build())
                .build()).block();
        System.out.println("挂起: state=" + sus.getState() + " | isSuspended=" + sus.isSuspended()
                + " | metadata=" + sus.getMetadata() + " | text=" + textOf(sus));

        for (String n : toolkit.getToolNames()) {
            AgentTool t = toolkit.getTool(n);
            System.out.println(n + " -> readOnly=" + t.isReadOnly()
                    + ", returnDirect=" + t.isReturnDirect() + ", strict=" + t.getStrict());
        }
    }
}
```

```bash
mvn -pl ai-agentscope compile exec:java -Dexec.mainClass=com.best.ai_agentscope.probe.ToolProbe
```

> 日志规范见 48 号：探针是独立 `main`，保留 `System.out`；应用组件一律 `@Slf4j`。

---

## 13. 下一篇预告

**51 · 第 5 章 · 权限与 HITL** —— 同一个 `LabTools`，这次给它上权限：五模式矩阵、deny/ask 规则、以及 `RequireUserConfirmEvent` 那条「模型要动手 → 人点头 → 才执行」的链路。本章的 `Toolkit` bean 直接复用。
