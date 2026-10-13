# 41 · ai-graph 的消息模型重构与循环治理

> **前置**：39/40 号已落地（`ai-graph` 抽取完成 + 编译修复完成）。
>
> **本文档回答**：「工具结果到底该不该用 Tool 消息回给模型」—— 以及在此之上的
> **`ai-graph` 全面优化方案**（P0 消息模型 / P1 调度器与容错 / P2 健壮性），
> 每一条都给出**完整文件**，直接整个替换。
>
> **第 2 节是你最该先看的一节**：本文档要整文件替换 11 个文件，
> 按你自己定的规矩，**「相对上一版，这几行是新增的」必须先列出来**。
>
> **本文档的前提证据全部是实测**：探针跑在 `/tmp`，编译时挂的是你**自己的**
> `ai-graph/target/classes`、`ai-memory/target/classes` 和 `~/.m2` 里的
> langchain4j 1.20.0 / langgraph4j 1.9.2。
> **没有改动你仓库里的任何一行代码。**

---

## 0. 先回答那个问题

### 0.1 你的直觉对了一半

**对的部分**：工具执行结果**确实**应该以 `ToolExecutionResultMessage` 的形式回给模型，
而不是伪装成一条自然语言 `AiMessage`。你现在的写法：

```java
// ToolExecuteNode:67-68 现状
return finishContinue(AiMessage.from(
        "[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text));
```

对模型来说，这条消息的角色是 **AI** —— 也就是「**我自己说过**：工具查到的结果是 total:0」。
它看不到「这是一次**已完成的工具调用**」，只能读到自己的一段叙述。而 `ExpertNode:39`
又用 `renderConversation` 把消息拍平成字符串喂给它 —— 于是一轮又一轮，模型看到的东西
**在语义上一模一样**，它没有理由相信查询已经完成，只能再查一次。这就是那 25 轮死循环。

> ★ **注意：你缺的不是结构，是消息类型。** `ToolExecuteNode` 里
> `finishContinue` / `finishDone` 这两个出口**你已经写对了**（见 5.8 的差异清单）——
> 拒绝/无通道走 `finishDone`（置 `PROPOSED_TOOL=NONE`），执行完走 `finishContinue`（保留）。
> 本单**不重写这个结构**，只把两个方法的**载荷**从 `AiMessage` 换成
> `ToolExecutionResultMessage`，再补一条同参熔断。

### 0.2 但只改 ToolExecuteNode 不够，有两个前提

**前提一：必须成对，且共用一个 id。**

OpenAI 兼容接口的协议要求：带 `tool_calls` 的 assistant 消息，后面必须紧跟 `role=tool`
的响应消息，且 `tool_call_id` 完全一致。所以：

| 谁写 | 写什么 | 必须带 |
|---|---|---|
| `ExpertNode` | `AiMessage(toolExecutionRequests=[...])` | `arguments` 是合法 JSON |
| `ToolExecuteNode` | `ToolExecutionResultMessage` | **同一个 `id`** |

你现在的 `ToolExecuteNode:56` 是 `id(UUID.randomUUID().toString())` —— **当场现生成一个 id**，
和专家那条 `AiMessage` 提议时写的 id **没有任何关联**。改完之后这个 id 必须**从 state 里取**。

**前提二：中间那一步不能把它拍平成字符串。**

`ChatMessageTexts.renderConversation(messages)`（`ExpertNode:39`）把消息列表变成一个字符串
再当 `@UserMessage` 发出去 —— 角色、工具配对、id 全部丢失。就算 state 里存的是真消息也白搭。

### 0.3 一句话结论

> **不是「工具结果要变成 Tool 消息」，而是「整条链路从头到尾都得是真消息」。**
> 专家写的提议是 `AiMessage(toolExecutionRequests)`，工具写的结果是
> `ToolExecutionResultMessage`，两条共用一个 id；喂模型时用**播种的 `ChatMemory`**，
> 而不是 `renderConversation`。

---

## 1. 实测证据（七条）

### 1.1 真工具消息能过检查点序列化 ✓

很多人不敢把工具消息放进 state，是怕 `RedisSaver` 序列化不了（毕竟 `UserMessage`
曾经就在这一步炸过 `NotSerializableException`）。实测**可以**：

```java
// /tmp/ToolMsgSer.java —— 用你自己的 MultiAgentState + LC4jStateSerializer
List<ChatMessage> original = List.of(
        SystemMessage.from("【系统提示词】"),
        UserMessage.from("帮我查一下订单"),
        AiMessage.from(request),                                   // 带 toolExecutionRequests
        ToolExecutionResultMessage.builder().id("call_1")
                .toolName("order__searchMultipartBizOrder")
                .text("{\"total\":0}").isError(false).build());

String stored = ser.writeDataAsString(data);   // 写入长度 = 1496 字节
MultiAgentState back = ser.readDataFromReader(new StringReader(stored));
```

输出：

```
读回 4 条：
  [SYSTEM] SystemMessage { text = "【系统提示词】" }
  [USER] UserMessage { text = "帮我查一下订单" }
  [AI] AiMessage { text = null, toolExecutionRequests = [ToolExecutionRequest { id = "call_1", ... }] }
  [TOOL_EXECUTION_RESULT] ToolExecutionResultMessage{id='call_1', toolName='...', contents=[...], isError=false}
整体 equals = true
id 配对保持 = true
```

原因在 `LC4jStateSerializer` 里有三个专用序列化器：

| 序列化器 | 覆盖字段 |
|---|---|
| `AiMessageSerializer` | `text` + `toolExecutionRequests` |
| `ToolExecutionRequestSerializer` | `id` / `name` / `arguments` |
| `ToolExecutionResultMessageSerializer` | `id` / `toolName` / `text` / `isError` / `attributes` |

> **所以不要走 `declareTransientAttributes(MESSAGES)` 那条路。** 它能让序列化不报错，
> 但代价是**重启后历史全丢**（之前验证过）。真工具消息本身是能存住的。

### 1.2 `@UserMessage List<ChatMessage>` 是陷阱 ✗

```java
public interface Bad {
    Proposal propose(@UserMessage List<ChatMessage> history);   // ← 千万别这么写
}
```

实测模型收到的：

```
[USER] contents = [ TextContent { text = "[UserMessage{...}, AiMessage{...},
       ToolExecutionResultMessage{...}] You must answer strictly in the following JSON format: ..." } ]
```

整个 List 被 `toString()` 成**一条** user 消息。这是最诱人的写法，也是最坑的写法。

### 1.3 正确姿势：播种 `ChatMemory` ✓

`ChatMemory` 是 langchain4j 里唯一能让模型看到「真消息列表」的口子。

```java
public class GraphChatMemory implements ChatMemory {
    private final List<ChatMessage> messages = new ArrayList<>();
    GraphChatMemory(List<ChatMessage> seed) { messages.addAll(seed); }
    @Override public Object id() { return "graph"; }
    @Override public void add(ChatMessage message) { messages.add(message); }
    @Override public List<ChatMessage> messages() { return List.copyOf(messages); }
    @Override public void clear() { messages.clear(); }
}

// 种子：系统提示词第一条 + 历史
List<ChatMessage> h2 = new ArrayList<>();
h2.add(SystemMessage.from("【种子里的系统提示词：角色/准则/工具清单】"));
h2.addAll(history());          // USER → AiMessage(toolExecutionRequests) → ToolExecutionResultMessage

ExpertProposal p = AiServices.builder(AgentNoSys.class)
        .chatModel(new StubModel())
        .chatMemory(new GraphChatMemory(h2))
        .build()
        .propose("那下一步呢");
```

模型收到的真实序列（`/tmp/MemProbe2.java` 输出，原样粘贴）：

```
[SYSTEM] SystemMessage { text = "【种子里的系统提示词：角色/准则/工具清单】" }
[USER]   UserMessage { text = "帮我查一下这个手机号的订单" }
[AI]     AiMessage { text = null, toolExecutionRequests = [ToolExecutionRequest { id = "call_1",
                  name = "order__searchMultipartBizOrder", arguments = "{\"phones\":[\"135****5811\"]}" }] }
[TOOL_EXECUTION_RESULT] ToolExecutionResultMessage{id='call_1', toolName='order__searchMultipartBizOrder',
                  contents=[TextContent { text = "{\"total\":0,\"data\":[]}" }]}
[USER]   UserMessage { text = "那下一步呢 You must answer strictly in the following JSON format: ..." }
```

**这才是模型该看到的东西**，而且结构化输出（record `Proposal`）在同一次调用里照常解析成功。

> ★ **系统提示词必须放进种子当第一条，不要用 `@SystemMessage` 注解。**
> 实测：保留注解时顺序会变成 `[历史…] → [SYSTEM] → [USER]` —— **系统提示词落在历史后面**，
> 指令权重被历史淹没。放进种子才是标准的 `[SYSTEM][USER][AI][TOOL][USER]`。

### 1.4 调度器同样能播种 + 返回枚举 ✓

调度器走的是「返回枚举」的路子，播种会不会和枚举解析打架？实测不打架
（`/tmp/SupProbe.java`）：

```
[SYSTEM] SystemMessage { text = "你是任务调度器。…可用路由（必须原样输出其中一个名字）：
         - order ：订单相关…  - analysis ：数据分析相关…  - knowledge ：…  - finish ：…" }
[USER]   UserMessage { text = "帮我查一下这个手机号的订单" }
[AI]     AiMessage { toolExecutionRequests = [ToolExecutionRequest { id = "call_1", ... }] }
[TOOL_EXECUTION_RESULT] ToolExecutionResultMessage{id='call_1', ...}
[USER]   UserMessage { text = "请给出你的路由决策。 You must answer strictly with one of these enums:
                  ORDER ANALYSIS KNOWLEDGE FINISH" }
解析结果 = ORDER
```

> ⚠️ **把这条证据的边界说清楚**：这个探针里的调度器是**我构造的**
> （系统提示词里带路由菜单、枚举上不写 `@Description`）。
> **你现在仓库里的 `SupervisorAgent` 不是这样** —— 它的 `@SystemMessage` 里
> **没有菜单**，只有一句「你是任务调度器…就选 FINISH」，菜单靠枚举上的 `@Description`
> 由框架自动带进去（那是 1.5 节说的「靠不住」的那条路）。
> 所以本节证明的是**「播种 + 枚举解析可以共存」这件事本身可行**，
> 而不是「你现在的调度器已经这么干了」。5.2/5.4 要做的
> **是把菜单显式写进提示词 —— 那是新增能力，不是修 bug。**

### 1.5 枚举 `@Description` 的两种命运 + 输入格式的容错边界

**（a）`@Description` 在「提示词模式」下有效，在「原生 JSON Schema 模式」下丢失。**

模型声明不支持 JSON Schema 时（`/tmp/EnumProbe.java` 变体 A）—— 描述**进得去**：

```
You must answer strictly with one of these enums:
ORDER - 订单相关：查订单、订单状态、金额
ANALYSIS - 数据分析相关：统计、跑 SQL
FINISH - 任务已完成，不需要再派发
```

模型声明支持 `Capability.RESPONSE_FORMAT_JSON_SCHEMA` 时（变体 B）—— 描述**没了**：

```
ResponseFormat { type = JSON, jsonSchema = JsonSchema { name = "Routing",
  rootElement = JsonObjectSchema { properties = { value = JsonEnumSchema {
      description = null, enumValues = [ORDER, ANALYSIS, FINISH] }}, required = [value] }}}
```

`description = null` —— 模型只看到三个裸枚举名。

> ⚠️ **这意味着「换一个模型/换一个网关，调度质量就变」**，而代码一行没改。
> **这正是你现在的处境**：`SupervisorAgent.Routing` 的四个 `@Description`
> 是调度语义的唯一来源，而它会不会被送到模型，取决于 provider 的
> `supportedCapabilities()`。所以 5.2 的做法是：**把路由语义显式写进系统提示词**，
> 并且**从同一份 `@Description` 反射生成**（`RoutingMenus`），
> 保证「提示词里写的」和「代码里声明的」不会漂移。

**（b）枚举解析的容错边界（决定要不要加 try/catch）：**

| 模型输出 | 结果 |
|---|---|
| `ORDER` | ✓ |
| `order` | ✓ **大小写不敏感** |
| `ORDER `（尾部空格） | ✓ **会 trim** |
| `"ORDER"`（带引号） | ✗ `OutputParsingException: Failed to parse ""ORDER""` |
| `我选ORDER`（带解释） | ✗ `OutputParsingException` |

**两种模式下模型被要求输出的格式还不一样**：提示词版要裸 `ORDER`，原生 schema 版要
`{"value":"ORDER"}`。模型「好心」多包一层引号，或者多打一个字，`SupervisorNode`
现在没有兜底 —— **整张图当场抛异常**（`SupervisorNode:29` 那个 catch 只包住了
`decider.decide(...)`，能兜住这一路；但换 provider 后连报错位置都会变）。
所以 P1 里给调度补 `ROUTE_TRACE` 和不炸图，不是可选项。

### 1.6 附：图追加消息的两条硬约束（`javap` 核实的）

写代码前先看清楚 `MESSAGES` 这个通道到底接受什么（`javap -c AppenderChannel`）：

| 事实 | 依据 | 影响 |
|---|---|---|
| update 值可以是**单个消息**，也可以是**整个 List** | `AppenderChannel.update` 里 `instanceof List` 判断，否则包装成单元素列表 | 一个节点**一次可以追加多条**消息 ✓ |
| reducer 是 `ReducerDisallowDuplicate`，按 `equals` **去重** | 字节码里 `stream().noneMatch(Objects.equals)` | 完全相同的消息不会重复入列（回放/重试时反而安全） |

> 这也解释了为什么你现有的 `Map.of(MESSAGES, AiMessage.from(...))` 一直能跑 ——
> 框架帮我们做了单元素包装。

### 1.7 ★ `SummarizingChatMemory` 的头部逐出会切出「孤儿 TOOL 消息」

这条**和 `ai-graph` 无关**，是另一条线（`assistant-demo` → `ChatAssistant` + MCP 工具），
但它是**同一个协议坑的另一面**，而且**我实测证明它在你的代码里已经存在**。
挂的是你**自己编译出来的** `ai-memory/target/classes`。

**Part A：先确认「挂了 tools 的 `AiServices` 会把工具消息写进 ChatMemory」** —— 这是前提：

```
助手回答 = 这个订单没有查到记录。
store 里最终落下的消息  —— 共 4 条
    [0] USER
    [1] AI(tool_calls id=call_1)
    [2] TOOL(id=call_1)
    [3] AI(text="这个订单没有查到记录。")
```

**Part B：把逐出边界切成正好切在工具配对中间**：

```
maxMessages=3，再加一条之后 messages()  —— 共 3 条
    [0] TOOL(id=call_9)   ← 孤儿
    [1] USER   [2] USER
    ★ 头部残留孤儿 TOOL 消息 = true ；同窗口内还有 AI(tool_calls) = false ；配对完整 = false
    被逐出的 = [AI(tool_calls id=call_9)]
maxMessages=4：无逐出，配对完整 = true
```

**结论**：`SummarizingChatMemory.add()`（`ai-memory/.../session/SummarizingChatMemory.java:52-57`）
的逐出是**无保护的头部截断**，它不认识「工具对」。逐出量恰好为 1 时（每次 `add` 一条新消息
就是这个量），只要窗口边界落在 `AiMessage(tool_calls)` 上，留下 `[TOOL][USER]...` ——
**一条 `role=tool` 消息，前面没有对应的 assistant 消息 → 下一次请求网关直接 400**。

只要会话用工具，**每跨一次窗口边界就踩一次**。修法在 7.5 节，**改 4 行**。

---

## 2. 「完整替换」差异清单 ★

> 你自己定的规矩：**凡「完整替换一个已有文件」，必须先列「相对上一版，这几行是新增的」**。
> 本文档要整文件替换 **11 个**已有文件，清单如下。
> **新增的 4 个类不在本表内**（它们没有「上一版」）。

### 2.1 `GraphProperties` —— 只加 5 个字段

| 位置 | 相对上一版的新增 |
|---|---|
| import | +1 行：**无**（`ToolRisk` 已在） |
| 字段 | **+5 行**：`maxSteps` / `maxRepeat` / `maxHistoryMessages` / `maxToolResultChars` / `recursionLimit`，各带一行 javadoc（共 +10 行） |
| 方法 | **不动**：`approvalMinRisk()` 已存在，不新增 |

### 2.2 `MultiAgentState` —— 换容器 + 加 7 个键

| 位置 | 相对上一版的新增 |
|---|---|
| 常量 | **+7 行**：`TOOL_CALL_ID` / `LAST_TOOL_SIGNATURE` / `REPEAT_COUNT` / `STEP_COUNT` / `FORCE_END` / `EXPERT_ERROR` / `ROUTE_TRACE` |
| `SCHEMA` | **改 1 行**：`Map.of(` → `Map.ofEntries(`；**+7 行** `Map.entry(...)`；原 10 行键值**每行都改成 `Map.entry(K, V)` 形式** |
| getter | **+7 行** |
| 旧代码 | **0 行删除**（10 个键和 getter 全部保留，`userId()`…`approvalDecided()` 一字不动） |

> ★ **`Map.of` 上限就是 10 对**，你现在正好 10 个。**不加键必须先换 `Map.ofEntries`**，
> 否则是编译错误（不是运行时），这一步没得省。

### 2.3 `ExpertAgent` —— 删注解、收参数

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| import | **−1 行** `SystemMessage`；**+1 行** `UserMessage` |
| 接口方法 | **改 1 行**：`propose(role, rules, tools, memory, request)` 五参 → `propose(@UserMessage String turnInstruction)` 一参 |
| `@SystemMessage` | **−1 个注解 + −10 行提示词**（搬进 `ExpertNode.buildSystemPrompt()`，见 5.6） |
| `ExpertProposal` record | **不动**（三个 `@Description` 一字不改） |

### 2.4 `ExpertNode` —— 改动最大，但仍保留原有骨架

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| 构造器 | **换参**：`(ExpertRouting, ExpertAgent, McpToolCatalog)` → `(ExpertRouting, ChatModel, McpToolCatalog, GraphProperties)` |
| 字段 | **−1** `ExpertAgent`（不再持有代理），**+1** `ChatModel`、**+1** `GraphProperties` |
| `apply` | **重写**：删掉 `renderConversation`（第 39 行）、删掉 catch 里伪装成「专家意见」的 `AiMessage` |
| 方法 | **+4**：`propose` / `buildSystemPrompt` / `resolveTool` / `abbreviate`；**−1** `normalizeTool`（改成不静默纠正的 `resolveTool`） |
| 原 `renderTools` / `toJson` / `safe` | **不动**（原文照抄） |
| 熔断/步数预算 | **+10 行**（本单新增能力） |

### 2.5 `ToolExecuteNode` —— ★ **只换载荷，不动结构**

> 这一节是本次重写**最重要**的一处修正。上一版文档让整个类重写、并发明了
> `writeBack(state, tool, callId, text, failed, keepLoop)` 这个带 boolean 的新名字 ——
> **那是把 32 号已经定好的命名又改了一遍**。

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| `finishContinue` / `finishDone` | ★ **名字一字不改，位置不动**（你现在是第 104/111 行）。只把参数类型 `AiMessage` → `ToolExecutionResultMessage`，并把方法体从 `Map.of(...)` 改成委托给 `writeBack(...)` |
| 构造器 | **+1 参** `GraphProperties`（3 参 → 4 参） |
| `apply` 的 4 个出口 | 4 处 `AiMessage.from(...)` → `resultMessage(callId, tool, text, failed)`（**只改这一句**，出口结构、`finishContinue`/`finishDone` 的选择逻辑**全部不变**） |
| `request.id` | **改 1 行**：`UUID.randomUUID().toString()` → `callIdOf(state)`（★ 这是 id 能配对上的唯一改动） |
| `request.arguments` | **改 1 行**：加空值兜底 `isBlank(...) ? "{}" : ...` |
| 方法 | **+4**：`writeBack` / `callIdOf` / `resultMessage` / `truncate`；**+2** 小工具 `isBlank` / `isNoTool`（你已有）；**−1** `abbreviate`（日志改成记长度） |
| `interrupt` | **一字不动**（审批闸门逻辑与 30 号一致） |

### 2.6 `MultiAgentGraphFactory` —— 5 处

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| 字段 | **+1** `StateSerializer<MultiAgentState> serializer`（把第 65 行的内联 `new LC4jStateSerializer<>(...)` 提出来） |
| 第 60-62 行 | **−3 行**：删掉共享的 `ExpertAgent expertAgent = AiServices.builder(...)`（连同 `import AiServices` / `import ExpertAgent` 两行） |
| 第 85 行 | **改 1 行**：`new ExpertNode(routing, expertAgent, toolCatalog)` → `new ExpertNode(routing, chatModelRegister.getDefault(), toolCatalog, graphProperties)` |
| 第 68 行 | **改 1 行**：`new ToolExecuteNode(...3 参)` → 4 参 |
| 第 141 行 | **改 1 行**：`.stateSerializer(new LC4jStateSerializer<AgentState>(...))` → `.stateSerializer(serializer)` |
| 第 113 行 | **改 1 行**：`.recursionLimit(25)` → `.recursionLimit(graphProperties.getRecursionLimit())` |
| 校验 | **+25 行**：3 条建图期 fail-fast（无专家 / 无结束标记 / fallback 不在路由表） |
| 建图日志 | **+4 行**（★ 这是「配置有没有被读到」的唯一验收点） |

### 2.7 `SupervisorNode` —— 2 处，各删 1 行

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| 第 31-34 行（catch） | **−1 行** `MESSAGES, AiMessage.from("（调度失败，直接收尾）")`；**+1 行** `ROUTE_TRACE` |
| 第 38-41 行（正常） | **−1 行** `MESSAGES, AiMessage.from("(调度：" + routing + ")")`；**+1 行** `ROUTE_TRACE` |
| import | **−1 行** `AiMessage` |
| 开头 | **+4 行** `forceEnd()` 短路（熔断后别再花一次模型调用） |

### 2.8 `SupervisorAgent`（ai-multiagent）

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| `@SystemMessage` | **−1 个注解 + −4 行提示词** |
| `route` 参数 | **改 1 行**：`@UserMessage String conversation` → `@UserMessage String turnInstruction` |
| `Routing` 枚举 | **一字不动**（4 个 `@Description` 全部保留 —— 它们现在的作用变成 `RoutingMenus` 的数据源） |
| import | **+1** `UserMessage`；`Description` 保留 |

### 2.9 `MultiAgentGraphConfig`（ai-multiagent）

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| `supervisorAgent` Bean | **整个删掉**（−8 行） |
| `multiAgentGraph` Bean | **改造**：decider 从 `messages -> supervisorAgent.route(render(messages))` 换成播种式内联（+8 行） |
| 常量 | **+1** `SUPERVISOR_SYSTEM`（含路由菜单插值） |
| 方法签名 | **+1 参** `GraphProperties` |
| import | **+4 / −3** |

> **删 `supervisorAgent` Bean 是安全的**：全项目只有这个文件引用它。
> 播种式做法的前提是「每次种子都不同」，单例代理做不到。

### 2.10 `application.yml`（ai-multiagent）

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| 第 74 行 | **改 1 个词**：`multiagent:` → `graph:`（★ 40 号的问题 3，至今没修） |
| 该段 | **+6 行**（5 个新配置项 + 注释） |
| `ai.mcp` 段 | **不动** |

### 2.11 `SummarizingChatMemory`（ai-memory）—— 4 行

| 位置 | 相对上一版的新增 / 删除 |
|---|---|
| `messages()` | **+4 行**：头部裁剪守卫（`while (head instanceof ToolExecutionResultMessage) remove(0)`） |
| 其他 | **0 行改动**（`add` / `set` / 摘要逻辑一字不动） |

---

## 3. 现状诊断：对着你此刻的代码

架构本身没问题：**「决策」与「执行」分离 + 按危险等级中断审批**，这是为了能在工具真正
执行前插一道人工闸门，比把 `ToolExecutionRequest` 直接交给 langchain4j 自动执行可控得多。
问题全部出在消息这一层（行号是本文档写作时的实际行号）：

| # | 位置 | 现状（★ 已对着代码逐行核对） | 对模型意味着什么 |
|---|---|---|---|
| 1 | `ExpertNode:39` | `ChatMessageTexts.renderConversation(state.messages())` 拍平成大字符串 | 消息结构全丢，循环的直接推手 |
| 2 | `ExpertNode:62` | `AiMessage.from("[" + role + "] " + safe(reply))` | 角色靠**内容前缀**认，不是消息角色 |
| 3 | `ExpertNode:51-57` | catch → `MESSAGES, AiMessage.from("[role] 处理失败：" + e.getMessage())` + `PROPOSED_TOOL, ""` | 专家异常被伪装成「专家的意见」，调度器无法区分 |
| 4 | `ExpertNode:69-81` | `normalizeTool` 把幻觉工具名**静默**改成 `NONE` | 模型永远不知道自己提错了，下轮还提同一个名字 |
| 5 | `ToolExecuteNode:67-68` | `finishContinue(AiMessage.from("[工具 X 结果]\n" + text))` | 「**我自己说过**工具结果 total:0」→ 认为没真查过 → 再查一次 |
| 6 | `ToolExecuteNode:72` | 异常 → `finishContinue(AiMessage.from("[工具 X 执行失败] …"))` | 同上：失败也伪装成自己的叙述 |
| 7 | `ToolExecuteNode:56` | `id(UUID.randomUUID().toString())` —— **当场现生成** | 与专家那条 `AiMessage` 提议写的 id **无关联**；一旦 5 里改成工具消息，这就是 400 的源头 |
| 8 | `ToolExecuteNode:58` | `arguments(state.proposedArgs())` —— **无空值兜底** | `proposedArgs` 为空串时，网关可能直接 400 |
| 9 | `ToolExecuteNode:46 / 51-52` | 拒绝、无通道 → `finishDone(...)` ✓ | ★ **这两个出口你已经写对了**，本单只换载荷类型 |
| 10 | `SupervisorNode:40` | `MESSAGES, AiMessage.from("(调度：" + routing + ")")` | 一句没头没尾、不知谁说的话，纯噪声且随轮数累积 |
| 11 | `SupervisorNode:33` | `MESSAGES, AiMessage.from("（调度失败，直接收尾）")` | 同上：模型会以为「我自己说过要收尾了」 |
| 12 | `MultiAgentGraphFactory:60-62` | 建**一个共享的** `ExpertAgent` 代理，第 85 行发给所有专家 | 无法按节点播种（播种的前提是「每次种子不同」） |
| 13 | `MultiAgentGraphFactory:113` | `recursionLimit(25)` 当业务预算用 | 按**超步**计，一轮专家→工具→调度约 3 个，25 只够约 8 轮 |
| 14 | `MultiAgentGraphFactory:65 / 141` | 图与 saver 各 `new` 了一个 `LC4jStateSerializer` | 行为一致所以没出事，但两处漂移起来是最难查的那种 bug |
| 15 | `MultiAgentState:25-35` | `Map.of` + **正好 10 个**键 | `Map.of` 上限就是 10 对，加键必须换 `Map.ofEntries` |
| 16 | `SummarizingChatMemory:52-57` | 头部逐出**无保护** | 另一条线（assistant-demo），见 1.7 / 7.5 |

**循环的成因链（对着 15:51 / 15:56 那两份日志看）：**

```
用户: 查一下 135****5811 的订单
  ↓
专家: tool=order__searchMultipartBizOrder args={"request":{"phones":["135****5811"]}}
  ↓
工具: total:0   →  finishContinue(AiMessage.from("[工具 …结果]\n total:0"))   ← 角色是 AI
  ↓
专家下一轮看到的东西 ≈「用户问了 X，我说过要查，我自己说查到的结果是 total:0」
  ↓
它没有理由相信"查询已由某个工具完成" → 再查一次，参数一字不差
  ↓
× 25 轮 → Maximum number of iterations (25) reached!
```

---

## 4. 你会敲出什么

| # | 文件 | 动作 | 优先级 |
|---|---|---|---|
| 1 | `ai-graph/.../config/GraphProperties.java` | 改：+5 个配置项 | P0 |
| 2 | `ai-graph/.../state/MultiAgentState.java` | 改：+7 个状态键（`Map.of` → `Map.ofEntries`） | P0 |
| 3 | `ai-graph/.../agent/GraphChatMemory.java` | **新增** | P0 |
| 4 | `ai-graph/.../agent/AgentMessages.java` | **新增**（窗口 + 种子） | P0 |
| 5 | `ai-graph/.../agent/SeededAgents.java` | **新增**（装配口） | P0 |
| 6 | `ai-graph/.../agent/ExpertAgent.java` | 改：去掉 `@SystemMessage` | P0 |
| 7 | `ai-graph/.../node/ExpertNode.java` | 改：写 `AiMessage(tool_calls)` | P0 |
| 8 | `ai-graph/.../node/ToolExecuteNode.java` | 改：★ 只换载荷类型 + id 改为 `callIdOf` | P0 |
| 9 | `ai-graph/.../graph/MultiAgentGraphFactory.java` | 改：共用序列化器 + 路由收口 | P0 |
| 10 | `ai-multiagent/src/main/resources/application.yml` | 改：`multiagent:` → `graph:` + 6 行 | P0 |
| 11 | `ai-graph/.../node/SupervisorNode.java` | 改：不再污染 `MESSAGES` | P1 |
| 12 | `ai-graph/.../graph/RoutingMenus.java` | **新增**（反射生成路由菜单） | P1 |
| 13 | `ai-multiagent/.../agent/SupervisorAgent.java` | 改：去 `@SystemMessage` | P1 |
| 14 | `ai-multiagent/.../config/MultiAgentGraphConfig.java` | 改：播种式 decider | P1 |
| 15 | `ai-memory/.../session/SummarizingChatMemory.java` | 改：★ 头部裁剪守卫（4 行） | P2 |
| 16 | `ai-graph/pom.xml` | 改：+1 个测试依赖 | P2 |
| 17 | `ai-graph/src/test/.../AgentMessagesTest.java` 等 3 个 | **新增**（离线回归） | P2 |
| 18 | `ai-multiagent/.../controller/AgentController.java` | 改：可选，同 threadId 串行 | P2 |

**新增 4 个类，修改 11 个文件。** 编译需要的顺序：先 1~6（依赖被后面用），再 7~9，再 10~14。

> ⚠️ **动手之前先查一件事**：`application.yml` 里现在写的还是 `ai.multiagent:`，
> 而 `GraphProperties` 读的是 `ai.graph.*` —— **40 号的问题 3 还没修**。
> 也就是说你现在所有配置项都在吃默认值。**先把它改掉**（第 5.10 节），
> 否则本单新加的 5 个配置项同样会静默失效，而且默认值恰好能跑，你**看不出来**。

---

## 5. P0：核心改造（完整代码）

### 5.1 `GraphProperties`（+5 个配置项）

`ai-graph/src/main/java/com/best/ai_graph/config/GraphProperties.java`

```java
package com.best.ai_graph.config;

import com.best.ai_common.mcp.ToolRisk;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.graph")
public class GraphProperties {

    /** 长期记忆的归属标识，默认取 spring.application.name */
    private String assistantId;

    /** 达到这个危险等级的工具需要人工批准 */
    private String approvalMinRisk = "high";

    /** checkpoint 的存活时间（小时） */
    private long checkpointTtlHours = 24;

    // ─────────── ★ 本单新增：循环治理参数 ───────────

    /** 一次 invoke 里专家最多决策几步，超了直接收尾（业务级预算，比 recursionLimit 可靠） */
    private int maxSteps = 8;

    /** 同一个工具用【完全相同】的参数连续处理几次就熔断 */
    private int maxRepeat = 3;

    /** 喂给模型的历史消息条数上限（种子窗口大小） */
    private int maxHistoryMessages = 20;

    /** 单个工具结果进上下文的字符上限，超出截断 */
    private int maxToolResultChars = 4000;

    /** langgraph4j 的兜底上限。它按【超步】计，建议 ≥ 3 × maxSteps */
    private int recursionLimit = 50;

    public ToolRisk approvalMinRisk() {
        return ToolRisk.parse(approvalMinRisk);
    }
}
```

> **为什么 `maxSteps` 和 `recursionLimit` 要各有一套。** `recursionLimit` 是框架的
> 最后一道保险（按超步计，而且节点数一变实际轮数就变，报错信息也不友好）；
> `maxSteps` 是**业务语义**上的「这个用户请求我最多帮它查几步」，基于它收尾时
> 我们还能给用户一句正常的话，而不是抛异常。

### 5.2 `MultiAgentState`（+7 个状态键）

`ai-graph/src/main/java/com/best/ai_graph/state/MultiAgentState.java`

```java
package com.best.ai_graph.state;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiAgentState extends AgentState {

    public static final String MESSAGES = "messages";
    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String NEXT = "next";
    public static final String MEMORY_CONTEXT = "memoryContext";
    public static final String APPROVED = "approved";
    public static final String PROPOSED_TOOL = "proposedTool";
    public static final String PROPOSED_ARGS = "proposedArgs";
    public static final String PROPOSED_BY = "proposedBy";
    public static final String APPROVAL_DECIDED = "approvalDecided";

    // ─────────── ★ 本单新增：工具消息配对 + 循环治理 ───────────

    /** 专家写 tool_calls 时记下的 id，工具结果靠它配对 */
    public static final String TOOL_CALL_ID = "toolCallId";

    /** 上一次工具调用的指纹（tool|args），用于识别"同参重复" */
    public static final String LAST_TOOL_SIGNATURE = "lastToolSignature";

    /** 同参重复次数 */
    public static final String REPEAT_COUNT = "repeatCount";

    /** 专家已决策的步数 */
    public static final String STEP_COUNT = "stepCount";

    /** 熔断标记：置 true 后专家不再决策，直接收尾 */
    public static final String FORCE_END = "forceEnd";

    /** 本轮专家是否调用失败（调度器可据此别再派给它） */
    public static final String EXPERT_ERROR = "expertError";

    /** 调度轨迹。★ 不是消息，不参与模型输入，只给日志/前端看 */
    public static final String ROUTE_TRACE = "routeTrace";

    /**
     * ★ 注意这里换成了 Map.ofEntries —— Map.of 最多只支持 10 对键值，
     * 原来正好 10 个，再加就编译不过（"cannot infer type" 那类报错）。
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(MESSAGES, Channels.<ChatMessage>appender(ArrayList::new)),
            Map.entry(USER_ID, Channels.base(() -> "")),
            Map.entry(SESSION_ID, Channels.base(() -> "")),
            Map.entry(NEXT, Channels.base(() -> "")),
            Map.entry(MEMORY_CONTEXT, Channels.base(() -> "")),
            Map.entry(APPROVED, Channels.base(() -> false)),
            Map.entry(PROPOSED_TOOL, Channels.base(() -> "")),
            Map.entry(PROPOSED_ARGS, Channels.base(() -> "")),
            Map.entry(PROPOSED_BY, Channels.base(() -> "")),
            Map.entry(APPROVAL_DECIDED, Channels.base(() -> false)),
            Map.entry(TOOL_CALL_ID, Channels.base(() -> "")),
            Map.entry(LAST_TOOL_SIGNATURE, Channels.base(() -> "")),
            Map.entry(REPEAT_COUNT, Channels.base(() -> 0)),
            Map.entry(STEP_COUNT, Channels.base(() -> 0)),
            Map.entry(FORCE_END, Channels.base(() -> false)),
            Map.entry(EXPERT_ERROR, Channels.base(() -> false)),
            Map.entry(ROUTE_TRACE, Channels.base(() -> ""))
    );

    public MultiAgentState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages(){
        return this.<List<ChatMessage>>value(MESSAGES).orElseGet(List::of);
    }

    public String userId()           { return this.<String>value(USER_ID).orElse(""); }
    public String sessionId()        { return this.<String>value(SESSION_ID).orElse(""); }
    public String next()             { return this.<String>value(NEXT).orElse(""); }
    public String memoryContext()    { return this.<String>value(MEMORY_CONTEXT).orElse(""); }
    public boolean approved()        { return this.<Boolean>value(APPROVED).orElse(false); }
    public String proposedTool()     { return this.<String>value(PROPOSED_TOOL).orElse(""); }
    public String proposedArgs()     { return this.<String>value(PROPOSED_ARGS).orElse("{}"); }
    public String proposedBy()       { return this.<String>value(PROPOSED_BY).orElse(""); }
    public boolean approvalDecided() { return this.<Boolean>value(APPROVAL_DECIDED).orElse(false); }

    // ─────────── ★ 新增 getter ───────────

    public String toolCallId()        { return this.<String>value(TOOL_CALL_ID).orElse(""); }
    public String lastToolSignature() { return this.<String>value(LAST_TOOL_SIGNATURE).orElse(""); }
    public int repeatCount()          { return this.<Integer>value(REPEAT_COUNT).orElse(0); }
    public int stepCount()            { return this.<Integer>value(STEP_COUNT).orElse(0); }
    public boolean forceEnd()         { return this.<Boolean>value(FORCE_END).orElse(false); }
    public boolean expertError()      { return this.<Boolean>value(EXPERT_ERROR).orElse(false); }
    public String routeTrace()        { return this.<String>value(ROUTE_TRACE).orElse(""); }
}
```

> ★ **`Channels.base` 是「覆盖」语义，不会累加。** 所以 `STEP_COUNT`、
> `REPEAT_COUNT` 必须由我们**自己**写成 `state.xxx() + 1`，不能指望框架叠加。

### 5.3 `GraphChatMemory`（新增）

`ai-graph/src/main/java/com/best/ai_graph/agent/GraphChatMemory.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次性的 ChatMemory：把图状态里的消息【原样】交给模型，调用完即弃。
 *
 * <p>存在的理由：图状态里的 MESSAGES 是 List&lt;ChatMessage&gt;，
 * 而 AiServices 的 @UserMessage 参数只能是 String 之类的标量 ——
 * 直接传 List 会被 toString() 拍成一条字符串（角色、工具配对全丢，见 41 号 1.2 节）。
 * 想让模型看到「真消息列表」，ChatMemory 是唯一的口子。
 *
 * <p>★ 真源永远是 state.messages()，这个类不做任何持久化、也不跨节点复用。
 */
public class GraphChatMemory implements ChatMemory {

    private final List<ChatMessage> messages = new ArrayList<>();

    public GraphChatMemory(List<ChatMessage> seed) {
        messages.addAll(seed);
    }

    @Override
    public Object id() {
        return "graph";
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        // 返回不可变快照：AiServices 内部会读它，避免被外部继续改
        return List.copyOf(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }
}
```

> ⚠️ **它和 `ai-memory` 里那个 `SummarizingChatMemory` 是什么关系？**
> 两个类都实现 `ChatMemory`，但职责相反，**不能互替**：
>
> | | `GraphChatMemory`（本单新增） | `SummarizingChatMemory`（你已有） |
> |---|---|---|
> | 真源 | `MultiAgentState.MESSAGES`（**检查点**） | `SessionMemoryStore`（Redis / 内存） |
> | 谁往里写 | 由节点 `put(MESSAGES, …)` 决定，图**不回写** | langchain4j 的 `AiServices` **自动** `add(...)` |
> | 生命周期 | 一次调用，用完即弃 | 跨请求长期存活（TTL 48h） |
> | 窗口 | `AgentMessages.window(...)` 由**调用方**裁 | `maxMessages` 在 `add()` **内部**逐出 |
> | 摘要 | 无 | 有（`ConversationSummarizer`） |
> | 装配方式 | `.chatMemory(...)` 每次新建 | `.chatMemoryProvider(...)`（单例代理） |
>
> ★ **图的 `AiServices` 绝对不要接 `SummarizingChatMemoryProvider`。**
> 一旦接上，langchain4j 会把每一轮的 `AiMessage` / `ToolExecutionResultMessage`
> **自动写进 store** —— 于是同一段对话同时活在「检查点」和「会话记忆」两处，
> 而 `graph.invoke` 恢复时读的**只有检查点**。两处只要有一次不一致
> （比如你按 6.1 节让 `SupervisorNode` 不再写消息、但记忆里还留着旧的），
> 排查时你会怀疑人生。**一个会话只能有一个真源 —— 图这条线的真源是检查点。**
>
> 反过来也说明另一件事：`ai-memory` 的会话记忆服务的是 `assistant-demo` 那条线
> （`ChatAssistant` + `@MemoryId`），**两条线各管各的**，本单不去打通它们
> （7.5 那条 4 行的修复是**修 bug**，不是打通）。

### 5.4 `AgentMessages`（新增，窗口 + 种子）

`ai-graph/src/main/java/com/best/ai_graph/agent/AgentMessages.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「图状态里的消息」整理成「喂给模型的消息」。
 */
public final class AgentMessages {

    private AgentMessages() {
    }

    /**
     * 组装种子：系统提示词打头，后面跟尾部窗口。
     *
     * <p>★ 系统提示词放在种子第一条，而不是用 @SystemMessage 注解 ——
     * 实测（41 号 1.3 节）：注解版的 SystemMessage 会被放在历史消息【之后】，
     * 变成 [USER][AI][TOOL][SYSTEM][USER]，指令权重被历史淹没。
     */
    public static List<ChatMessage> seed(String systemPrompt, List<ChatMessage> all, int maxHistory) {
        List<ChatMessage> seed = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            seed.add(SystemMessage.from(systemPrompt));
        }
        seed.addAll(window(all, maxHistory));
        return seed;
    }

    /**
     * 取尾部窗口，并保证工具调用的「一问一答」不被裁散。
     *
     * <p>★ 这是硬约束不是优化：OpenAI 兼容接口要求每个 tool 消息都能找到它对应的
     * assistant(tool_calls) 消息。裁出一个「孤儿 tool 消息」会直接 400。
     * 所以头部一旦是工具结果，就整条丢掉，直到头部不是工具结果为止。
     *
     * <p>★ 注意：同样的逻辑在 ai-memory 的 SummarizingChatMemory 里也【必须】有一份
     * —— 那是另一条线（assistant-demo），它逐出的边界同样会切在工具对中间（见 1.7 / 7.5）。
     * 两处不能共用一个工具类：ai-memory 不依赖 langchain4j 之外的东西，
     * 而 ai-graph 依赖 ai-memory，反向依赖会造成循环。
     */
    public static List<ChatMessage> window(List<ChatMessage> all, int maxHistory) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (maxHistory <= 0 || all.size() <= maxHistory) {
            return List.copyOf(all);
        }
        List<ChatMessage> tail = new ArrayList<>(all.subList(all.size() - maxHistory, all.size()));
        while (!tail.isEmpty() && tail.get(0) instanceof ToolExecutionResultMessage) {
            tail.remove(0);
        }
        return tail;
    }
}
```

### 5.5 `SeededAgents`（新增，装配口）

`ai-graph/src/main/java/com/best/ai_graph/agent/SeededAgents.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * 「播种 → 调用 → 丢弃」的 AiServices 装配口。
 *
 * <p>为什么每次调用都重新 build：AiServices 的 chatMemory 是【构造期】注入的，
 * 而每个节点的种子（系统提示词 + 历史窗口）都不一样。
 *
 * <p>★ 这个 build 比看上去便宜得多 —— 底层的 ChatModel【不是】新建的：
 * chatModelRegister.getDefault() 走 ModelRegister 里那个
 * Map&lt;String, M&gt; cache（computeIfAbsent，按 profile 缓存），同一个 profile
 * 全程只有一个 ChatModel 实例、HTTP 连接池也复用。每次 build() 真正新建的
 * 只有一层动态代理。
 *
 * <p>如果哪天这层代理真成了瓶颈，本项目已有的答案是
 * {@code .chatMemoryProvider(...)}：单例代理 + provider 按 memoryId 现造记忆，
 * assistant-demo 的 ChatAssistant 就是这么装配的（见 SummarizingChatMemoryProvider）。
 * 而【不是】ThreadLocal —— 本项目里的 ThreadLocal 只用在 AiUserContext 的
 * 用户身份透传上（配 Scope/wrap 给异步任务用），没有任何一处拿它缓存会话态。
 */
public final class SeededAgents {

    private SeededAgents() {
    }

    public static <T> T build(Class<T> iface, ChatModel chatModel, List<ChatMessage> seed) {
        return AiServices.builder(iface)
                .chatModel(chatModel)
                .chatMemory(new GraphChatMemory(seed))
                .build();
    }
}
```

### 5.6 `ExpertAgent`（去掉 `@SystemMessage`）

`ai-graph/src/main/java/com/best/ai_graph/agent/ExpertAgent.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.UserMessage;

import java.util.Map;

/**
 * 专家决策接口。
 *
 * <p>★ 这里【没有】@SystemMessage —— 系统提示词由调用方拼好，
 * 作为种子的第一条 SystemMessage 传进来（见 {@link AgentMessages#seed}）。
 * 这是 41 号 1.3 节的实测结论：注解版会被放到历史消息后面。
 *
 * <p>原来 @SystemMessage 里的「输出要求」没有丢 —— 它搬进了
 * {@code ExpertNode.buildSystemPrompt()}。38 号定的规则不变：
 * 框架契约（含输出要求）写在一处，专家只写自己的工作准则。
 */
public interface ExpertAgent {

    ExpertProposal propose(@UserMessage String turnInstruction);

    record ExpertProposal(
            @Description("给用户的处理意见或结论，用中文回答，回答简洁")
            String reply,

            @Description("要调用的工具名，必须从工具清单中选；不需要工具时填NONE")
            String tool,

            @Description("工具调用参数，JSON对象；不需要调用工具填空对象")
            Map<String, Object> arguments
    ) {}
}
```

### 5.7 `ExpertNode`（写 `AiMessage(tool_calls)`）

`ai-graph/src/main/java/com/best/ai_graph/node/ExpertNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_graph.agent.AgentMessages;
import com.best.ai_graph.agent.ExpertAgent;
import com.best.ai_graph.agent.SeededAgents;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.state.MultiAgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ExpertNode implements NodeAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每回合的固定指令。真正的内容全在种子的系统提示词里。 */
    private static final String TURN_INSTRUCTION = "请根据以上对话，给出你的下一步决策。";

    private final ExpertRouting routing;
    private final ChatModel chatModel;
    private final McpToolCatalog toolCatalog;
    private final GraphProperties graphProperties;

    public ExpertNode(ExpertRouting routing,
                      ChatModel chatModel,
                      McpToolCatalog toolCatalog,
                      GraphProperties graphProperties) {
        this.routing = routing;
        this.chatModel = chatModel;
        this.toolCatalog = toolCatalog;
        this.graphProperties = graphProperties;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String role = routing.expertRole();
        String clientKey = routing.edgeKey();
        List<McpTool> tools = toolCatalog.list(clientKey);

        Map<String, Object> update = new HashMap<>();
        // ★ Channels.base 是覆盖语义，累加只能自己算
        int step = state.stepCount() + 1;
        update.put(MultiAgentState.STEP_COUNT, step);
        update.put(MultiAgentState.EXPERT_ERROR, false);

        // ─────────── 熔断 / 步数预算：不再问模型，直接给一句收尾话 ───────────
        if (state.forceEnd() || step > graphProperties.getMaxSteps()) {
            log.warn("专家 {} 停止决策: step={} forceEnd={}", role, step, state.forceEnd());
            update.put(MultiAgentState.MESSAGES, AiMessage.from(
                    "这个问题需要多轮操作，但已经达到本轮处理上限，我先停在这里。"
                            + "把问题拆细一点再问我，或者告诉我你想先看哪一部分。"));
            update.put(MultiAgentState.PROPOSED_TOOL, NO_TOOL);
            return update;
        }

        // ─────────── 决策（真消息播种，不再 renderConversation） ───────────
        ExpertAgent.ExpertProposal proposal;
        try (var ignored = AiUserContext.open(state.userId())) {
            proposal = propose(state, role, tools);
        } catch (Exception e) {
            log.error("专家 {} 处理失败", role, e);
            // ★ 不伪装成"专家的意见"：用 SystemMessage 明说这是系统报错
            update.put(MultiAgentState.EXPERT_ERROR, true);
            update.put(MultiAgentState.MESSAGES, SystemMessage.from(
                    "【系统】" + role + " 本次调用失败（" + e.getClass().getSimpleName()
                            + "），它的结论不可用。请改派其他助手；若没有合适的助手就直接收尾。"));
            update.put(MultiAgentState.PROPOSED_TOOL, NO_TOOL);
            return update;
        }

        String proposed = proposal.tool() == null ? "" : proposal.tool().trim();
        String tool = resolveTool(proposed, tools);

        List<ChatMessage> newMessages = new ArrayList<>();
        if (NO_TOOL.equals(tool)) {
            // 幻觉工具名：明确告诉模型"没有这个工具"，别静默吞掉
            if (!proposed.isEmpty() && !NO_TOOL.equalsIgnoreCase(proposed)) {
                log.warn("专家 {} 提议了不在清单里的工具 [{}]，可用: {}", role, proposed, names(tools));
                newMessages.add(SystemMessage.from(
                        "【系统】没有名为 \"" + proposed + "\" 的工具。本次可用的工具只有："
                                + (tools.isEmpty() ? "（无）" : String.join(", ", names(tools)))
                                + "。请从里面重新选，或者不要工具、直接给出结论。"));
            }
            newMessages.add(AiMessage.from(safe(proposal.reply())));
            update.put(MultiAgentState.PROPOSED_TOOL, NO_TOOL);
            update.put(MultiAgentState.PROPOSED_ARGS, "{}");
            log.info("专家 {} 结论（不调工具）: {}", role, abbreviate(safe(proposal.reply())));
        } else {
            // ★★ 核心：写一条【带 toolExecutionRequests 的 Ai 消息】，
            //    并把 id 记进 state，供 ToolExecuteNode 写结果时配对。
            String callId = "call_" + UUID.randomUUID();
            String args = toJson(proposal.arguments());
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(callId)
                    .name(tool)
                    .arguments(args)
                    .build();
            newMessages.add(AiMessage.from(request));   // text 为 null，这是协议要求的形态
            update.put(MultiAgentState.TOOL_CALL_ID, callId);
            update.put(MultiAgentState.PROPOSED_TOOL, tool);
            update.put(MultiAgentState.PROPOSED_ARGS, args);
            log.info("专家 {} 提议工具: {} id={} 参数: {}", role, tool, callId, args);
        }

        update.put(MultiAgentState.PROPOSED_BY, clientKey);
        // ★ MESSAGES 的值可以是整个 List → 一次追加多条（41 号 1.6 节 javap 核实）
        update.put(MultiAgentState.MESSAGES, newMessages);
        return update;
    }

    // ───────────────────────── 内部方法 ─────────────────────────

    private ExpertAgent.ExpertProposal propose(MultiAgentState state, String role, List<McpTool> tools) {
        String systemPrompt = buildSystemPrompt(state, role, tools);
        List<ChatMessage> seed = AgentMessages.seed(
                systemPrompt, state.messages(), graphProperties.getMaxHistoryMessages());
        return SeededAgents.build(ExpertAgent.class, chatModel, seed).propose(TURN_INSTRUCTION);
    }

    /** 系统提示词骨架：38 号定的结构原样保留，「输出要求」跟着骨架一起留在这里。 */
    private String buildSystemPrompt(MultiAgentState state, String role, List<McpTool> tools) {
        String rules = routing.expert() == null || routing.expert().rules() == null
                ? "(无额外准则，按通用要求执行)"
                : routing.expert().rules();
        String memory = state.memoryContext().isBlank() ? "(无)" : state.memoryContext();
        return """
                你是%s。

                ## 你的工作准则
                %s

                ## 你可以调用的工具
                %s

                ## 已知的用户信息（可能为空）
                %s

                ## 输出要求
                请针对用户的请求给出处理意见。
                - 需要调用工具时：tool 字段填工具名，arguments 字段填参数对象
                - 不需要调用工具时：tool 填 NONE，arguments 填空对象
                - 一次只提议一个工具；看到它的返回结果后，再决定是否需要下一个
                - 只能从上面的工具清单里选，不要编造工具名
                - 不要假装已经执行了 —— 真正的执行由后续环节完成
                - ★ 如果对话里已经出现【工具返回结果】，说明那次调用已经真的执行过了：
                  不要用同样的参数再调一次；有结论就直接回答，需要别的数据就换工具或换参数
                """.formatted(role, rules, renderTools(tools), memory);
    }

    /** 只做"合不合法"的判断，不做静默纠正（纠正动作在 apply 里，会回一句系统提示） */
    private String resolveTool(String proposed, List<McpTool> available) {
        if (proposed.isEmpty() || NO_TOOL.equalsIgnoreCase(proposed)) {
            return NO_TOOL;
        }
        boolean exists = available.stream().anyMatch(t -> t.name().equals(proposed));
        return exists ? proposed : NO_TOOL;
    }

    private static List<String> names(List<McpTool> tools) {
        return tools.stream().map(McpTool::name).toList();
    }

    private String renderTools(List<McpTool> tools) {
        if (tools.isEmpty()) {
            return "(本次没有可用工具，如果确实需要执行操作，请说明你缺少什么工具)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            sb.append("- ").append(tool.name())
                    .append(" [危险等级: ").append(tool.risk()).append("]\n")
                    .append("  说明: ").append(safe(tool.description())).append('\n')
                    .append("  参数: ").append(tool.parameters()).append('\n');
        }
        return sb.toString();
    }

    private String toJson(Map<String, Object> args){
        if(args == null || args.isEmpty()){
            return "{}";
        }
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e){
            log.warn("工具参数序列化失败，按空对象处理: {}", args, e);
            return "{}";
        }
    }

    private static String abbreviate(String s){
        if(s == null){
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }

    private static String safe(String s){
        return s == null ? "" : s;
    }
}
```

> **两个容易漏掉的点：**
>
> 1. `AiMessage.from(request)` 的 **`text` 是 `null`**。这是协议要求的形态（assistant
>    消息带 tool_calls 时 content 可以为空）。好消息是 `AgentController.lastAiText()`
>    本来就跳过 `text == null` 的 Ai 消息，**前端答复逻辑不用改**。
> 2. 幻觉工具名走的是 `newMessages` **两条**（系统纠正提示 + 专家的话），
>    不是 `update.put` 两次 —— `Map` 同一个 key 后 put 会覆盖前 put。
>    这正是 `MESSAGES` 通道接受 List 的用武之地。

### 5.8 `ToolExecuteNode`（★ 只换载荷，结构不动）

`ai-graph/src/main/java/com/best/ai_graph/node/ToolExecuteNode.java`

> ★ **先说清楚这一版和你现在那份的关系**（对照 2.5 的差异清单）：
> - `finishContinue` / `finishDone` **两个名字保留**，`interrupt` **一字不动**；
> - 4 个出口里只把 `AiMessage.from(...)` 换成 `resultMessage(...)`；
> - `id` 从 `UUID.randomUUID()` 换成 `callIdOf(state)` —— **这是 id 能配对上的唯一改动**；
> - 新增 `writeBack` 作为两个出口的**共同实现**（同参熔断 + 状态回写），
>   取代上一版文档里那个 `writeBack(..., keepLoop)` 的新命名。

```java
package com.best.ai_graph.node;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.state.MultiAgentState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public class ToolExecuteNode implements NodeAction<MultiAgentState>, InterruptibleAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private final McpClientRegister register;

    private final McpToolCatalog toolCatalog;

    private final ToolRisk approvalMinRisk;

    private final GraphProperties graphProperties;

    public ToolExecuteNode(McpClientRegister register,
                           McpToolCatalog toolCatalog,
                           ToolRisk approvalMinRisk,
                           GraphProperties graphProperties) {
        this.register = register;
        this.toolCatalog = toolCatalog;
        this.approvalMinRisk = approvalMinRisk;
        this.graphProperties = graphProperties;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String tool = state.proposedTool();
        if (isNoTool(tool)) {
            return Map.of();
        }
        // ★ 改动点 1：id 不再是现生成的 UUID，而是专家写那条 AiMessage 时记下的 id
        String callId = callIdOf(state);

        // ─────────── 人工拒绝：也必须回一条【工具结果消息】 ───────────
        // ★ 不能写普通 AiMessage —— 前面那条 assistant(tool_calls) 必须有 tool 响应，
        //   否则下一轮把消息列表发给网关时会 400。走 finishDone：回调度器重新决策。
        if (state.approvalDecided() && !state.approved()) {
            log.info("工具{}被人工拒绝", tool);
            return finishDone(state, tool, callId,
                    resultMessage(callId, tool, "用户拒绝了这次操作，工具没有执行。", true));
        }

        McpClient client = register.get(state.proposedBy());
        if (client == null) {
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", state.proposedBy(), tool);
            return finishDone(state, tool, callId, resultMessage(callId, tool,
                    "未配置 " + state.proposedBy() + " 的 MCP 通道，这个工具调不了。", true));
        }

        boolean failed;
        String text;
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(callId)
                    .name(tool)
                    // ★ 改动点 2：参数空值兜底，避免 proposedArgs 为空串时被网关 400
                    .arguments(isBlank(state.proposedArgs()) ? "{}" : state.proposedArgs())
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            failed = result.isError();
            text = result.resultText();
        } catch (Exception e) {
            log.error("工具执行异常: tool={} by={} args={}",
                    tool, state.proposedBy(), state.proposedArgs(), e);
            failed = true;
            text = "工具执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        text = truncate(safeText(text));
        log.info("工具执行{}: tool={} id={} by={} 结果长度={}",
                failed ? "返回错误" : "成功", tool, callId, state.proposedBy(), text.length());

        // ★ 走 finishContinue（不是 finishDone）：保留 proposedTool，
        //   因为条件边要靠它判断"这一轮循环要不要继续"。
        return finishContinue(state, tool, callId, resultMessage(callId, tool, text, failed));
    }

    // ─────────── 审批闸门（与 30 号一致，本次未改动任何一行） ───────────

    @Override
    public Optional<InterruptionMetadata<MultiAgentState>> interrupt(String nodeId, MultiAgentState state, RunnableConfig config) {
        String tool = state.proposedTool();
        if(isNoTool(tool)){
            return Optional.empty();
        }
        if(state.approvalDecided()){
            return Optional.empty();
        }
        ToolRisk risk = toolCatalog.riskOf(tool);
        if(!risk.atLeast(approvalMinRisk)){
            log.info("工具 {} 危险等级={} 未达审批阈值={}，直接执行", tool, risk, approvalMinRisk);
            return Optional.empty();
        }
        log.info("工具 {} 危险等级={} 达到审批阈值={}，暂停等待人工批准", tool, risk, approvalMinRisk);
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .reason("工具 " + tool + " 危险等级为 " + risk + "，需要人工批准").build());
    }

    // ───────────────────────── 两个出口（★ 名字保留，只换载荷类型） ─────────────────────────

    /**
     * 循环继续：保留 proposedTool → 条件边回到提议它的那个专家。
     *
     * <p>载荷从 AiMessage 换成了 ToolExecutionResultMessage —— 这是本单的核心改动：
     * 模型看到的不再是"我自己说过工具查到了 X"，而是"有一次工具调用、结果是 X"。
     */
    private Map<String, Object> finishContinue(MultiAgentState state, String tool, String callId,
                                               ToolExecutionResultMessage result) {
        return writeBack(state, tool, callId, result, false);
    }

    /**
     * 循环结束：置 proposedTool=NONE → 条件边走 LOOP_END 回调度器。
     * 拒绝 / 无通道 / 熔断走这里。
     */
    private Map<String, Object> finishDone(MultiAgentState state, String tool, String callId,
                                           ToolExecutionResultMessage result) {
        return writeBack(state, tool, callId, result, true);
    }

    /**
     * 两个出口的共同实现：结果消息 + 同参重复计数 + 熔断 + 状态回写。
     *
     * @param end true  = 强制结束循环（拒绝/无通道），proposedTool 置 NONE
     *            false = 常规执行完，是否结束取决于同参重复次数
     */
    private Map<String, Object> writeBack(MultiAgentState state, String tool, String callId,
                                          ToolExecutionResultMessage result, boolean end) {
        String signature = tool + "|" + state.proposedArgs();
        int repeat = signature.equals(state.lastToolSignature()) ? state.repeatCount() + 1 : 1;
        boolean forceEnd = end || repeat >= graphProperties.getMaxRepeat();
        if (forceEnd && !end) {
            log.warn("同参处理 {} 次 → 熔断。signature={}", repeat, signature);
        }

        List<ChatMessage> newMessages = new ArrayList<>();
        newMessages.add(result);
        if (forceEnd && !end) {
            // ★ 熔断时必须额外说一句话：只说"结果没变"模型未必听得懂
            newMessages.add(SystemMessage.from(
                    "【系统】同一个工具用完全相同的参数已经处理了 " + repeat + " 次，结果不会再有变化，"
                            + "禁止再次调用它。请直接用已有的信息给出结论。"));
        }

        Map<String, Object> update = new HashMap<>();
        update.put(MultiAgentState.MESSAGES, newMessages);
        update.put(MultiAgentState.PROPOSED_TOOL, forceEnd ? NO_TOOL : tool);
        update.put(MultiAgentState.TOOL_CALL_ID, "");
        update.put(MultiAgentState.LAST_TOOL_SIGNATURE, signature);
        update.put(MultiAgentState.REPEAT_COUNT, repeat);
        update.put(MultiAgentState.FORCE_END, forceEnd);
        update.put(MultiAgentState.APPROVAL_DECIDED, false);
        return update;
    }

    /**
     * 取出这次工具调用的 id。
     *
     * <p>优先用 state 里记的；没有就从最近一条带 tool_calls 的 Ai 消息里【回捞】——
     * 这样升级前的旧检查点也能自愈，不会因为缺一个 state 键就出现 id 对不上的 400。
     */
    private static String callIdOf(MultiAgentState state) {
        String recorded = state.toolCallId();
        if (!isBlank(recorded)) {
            return recorded;
        }
        List<ChatMessage> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage am
                    && am.hasToolExecutionRequests()
                    && !am.toolExecutionRequests().isEmpty()) {
                String id = am.toolExecutionRequests().get(0).id();
                log.warn("state 里没有 toolCallId，从消息历史回捞到 id={}", id);
                return id;
            }
        }
        String generated = "call_" + UUID.randomUUID();
        log.warn("回捞不到 toolCallId，生成兜底 id={} —— 这次的结果可能配不上前面那条消息", generated);
        return generated;
    }

    /** ★ isError 字段是真的（javap 确认），但很多网关不读它 —— 所以文本里也写清楚了 */
    private static ToolExecutionResultMessage resultMessage(String callId, String tool,
                                                            String text, boolean failed) {
        return ToolExecutionResultMessage.builder()
                .id(callId)
                .toolName(tool)
                .text(text)
                .isError(failed)
                .build();
    }

    private String truncate(String text) {
        int max = graphProperties.getMaxToolResultChars();
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（结果过长，已截断 " + (text.length() - max) + " 字）";
    }

    private static String safeText(String s){
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s){
        return s == null || s.isBlank();
    }

    private static boolean isNoTool(String tool){
        return tool == null || tool.isBlank() || NO_TOOL.equals(tool);
    }
}
```

> **`isError` 这个字段是真的**（`javap` 确认 `ToolExecutionResultMessage.Builder.isError(Boolean)`
> 存在，序列化器也写它），但**很多网关不读它** —— 所以文本里仍要写清楚
> 「工具执行异常: …」。字段是给框架/日志用的，文本是给模型用的，两个都要有。

### 5.9 `MultiAgentGraphFactory`（共用序列化器 + 路由收口）

`ai-graph/src/main/java/com/best/ai_graph/graph/MultiAgentGraphFactory.java`

```java
package com.best.ai_graph.graph;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.node.*;
import com.best.ai_graph.state.MultiAgentState;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MultiAgentGraphFactory {

    private static final String LOOP_END = "loopEnd";

    private static final String NO_TOOL = "NONE";

    /**
     * ★ 图和 saver 共用【同一个】序列化器实例。
     * 原来是 new 了两个（图的泛型是 MultiAgentState，saver 的写成了 AgentState）——
     * 行为一致所以没出过问题，但两处漂移起来就是最难查的那种 bug。
     */
    private final StateSerializer<MultiAgentState> serializer = new LC4jStateSerializer<>(MultiAgentState::new);

    private final ChatModelRegister chatModelRegister;
    private final McpToolCatalog toolCatalog;
    private final McpClientRegister mcpRegister;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder memoryRecorder;
    private final RedisProperties redisProperties;
    private final GraphProperties graphProperties;
    private final String applicationName;

    public MultiAgentGraphFactory(ChatModelRegister chatModelRegister, McpToolCatalog toolCatalog,
                                  McpClientRegister mcpRegister, MemoryRecallService recallService,
                                  UserMemoryRecorder memoryRecorder, RedisProperties redisProperties,
                                  GraphProperties graphProperties, String applicationName) {
        this.chatModelRegister = chatModelRegister;
        this.toolCatalog = toolCatalog;
        this.mcpRegister = mcpRegister;
        this.recallService = recallService;
        this.memoryRecorder = memoryRecorder;
        this.redisProperties = redisProperties;
        this.graphProperties = graphProperties;
        this.applicationName = applicationName;
    }

    public <E extends Enum<E> & ExpertRouting> CompiledGraph<MultiAgentState> createAgent(
            Class<E> routingEnum,
            RouteDecider decider,
            String fallbackEdgeKey
    ) throws GraphStateException {
        String assistantId = resolveAssistantId();
        RedisSaver saver = buildRedisSaver(assistantId);
        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, serializer)
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(decider, fallbackEdgeKey)))
                .addNode(GraphNodes.TOOL_EXECUTE, node(new ToolExecuteNode(
                        mcpRegister, toolCatalog, graphProperties.approvalMinRisk(), graphProperties)))
                .addNode(GraphNodes.WRITE_MEMORY, node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, GraphNodes.RECALL_MEMORY)
                .addEdge(GraphNodes.RECALL_MEMORY, GraphNodes.SUPERVISOR)
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);

        Map<String, String> supervisorRoutes = new HashMap<>();
        Map<String, String> toolRoutes = new HashMap<>();
        int expertCount = 0;
        boolean hasEndMarker = false;
        for (E routing : routingEnum.getEnumConstants()) {
            String key = routing.edgeKey();
            String role = routing.expertRole();
            if(role == null){
                supervisorRoutes.put(key, GraphNodes.WRITE_MEMORY);
                hasEndMarker = true;
                continue;
            }
            // ★ 专家不再共享一个"建好的 ExpertAgent 代理"：它每次调用都要按自己的种子重建
            graph.addNode(key, node(new ExpertNode(
                    routing, chatModelRegister.getDefault(), toolCatalog, graphProperties)));
            graph.addEdge(key, GraphNodes.TOOL_EXECUTE);
            supervisorRoutes.put(key, key);
            toolRoutes.put(key, key);
            expertCount++;
        }
        if(expertCount == 0){
            throw new IllegalArgumentException("路由枚举 " + routingEnum.getSimpleName()
                    + " 里没有任何专家（expertRole() 全为 null）");
        }
        if(!hasEndMarker){
            throw new IllegalArgumentException("路由枚举 " + routingEnum.getSimpleName()
                    + " 里没有'结束'标记（expertRole() 为 null 的值）—— supervisor 将永远无法收尾，"
                    + "图会一直转到 recursionLimit");
        }
        if (!supervisorRoutes.containsKey(fallbackEdgeKey)) {
            throw new IllegalArgumentException("fallbackEdgeKey=[" + fallbackEdgeKey
                    + "] 不在 " + routingEnum.getSimpleName() + " 的路由表里。可用: "
                    + supervisorRoutes.keySet());
        }
        toolRoutes.put(LOOP_END, GraphNodes.SUPERVISOR);
        log.info("建图完成: assistantId={} 专家数={} 审批阈值={} 步数上限={} 重复熔断={} 历史窗口={}",
                assistantId, expertCount, graphProperties.approvalMinRisk(),
                graphProperties.getMaxSteps(), graphProperties.getMaxRepeat(),
                graphProperties.getMaxHistoryMessages());
        return graph
                .addConditionalEdges(GraphNodes.SUPERVISOR, AsyncEdgeAction.edge_async(MultiAgentState::next), supervisorRoutes)
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE, AsyncEdgeAction.edge_async(MultiAgentGraphFactory::routeAfterTool), toolRoutes)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        // ★ 上面那条日志就是"配置到底有没有被读到"的验收点，别删
                        .recursionLimit(graphProperties.getRecursionLimit())
                        .releaseThread(false)
                        .build());
    }

    /**
     * 工具节点之后往哪走。
     *
     * <p>正常情况：proposedTool 还被保留着 → 回到提议它的那个专家继续判断。
     * 熔断/拦截：proposedTool 已被置 NONE（见 ToolExecuteNode.writeBack）→ 回调度器收尾。
     */
    private static String routeAfterTool(MultiAgentState state){
        // 防御性再查一次熔断标记：万一将来有别的节点改了 state
        if (state.forceEnd()) {
            return LOOP_END;
        }
        String tool = state.proposedTool();
        if(tool == null || tool.isBlank() || NO_TOOL.equals(tool)){
            return LOOP_END;
        }
        String by = state.proposedBy();
        if(by == null || by.isBlank()){
            log.warn("proposedTool={} 非空但 proposedBy 为空，无法确定回哪个专家，改为回调度器", tool);
            return LOOP_END;
        }
        return by;
    }

    private String resolveAssistantId(){
        String configured = graphProperties.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }

    private RedisSaver buildRedisSaver(String namespace){
        var builder = RedisSaver.builder()
                .host(redisProperties.getHost())
                .port(redisProperties.getPort())
                .stateSerializer(serializer)          // ★ 与图共用同一个实例
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(namespace))
                .ttl(graphProperties.getCheckpointTtlHours(), TimeUnit.HOURS);
        String password = redisProperties.getPassword();
        if(password != null && !password.isBlank()){
            builder.password(password);
        }
        return builder.build();
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action){
        return AsyncNodeAction.node_async(action);
    }
}
```

> ⚠️ **`RouteDecider` 的签名没变**（还是 `decide(List<ChatMessage>)`），
> 所以 6.4 的播种逻辑完全写在 `MultiAgentGraphConfig` 里，`ai-graph` 不需要知道
> 「怎么播种」——这条边界继续保持。

### 5.10 `application.yml`（★ 先把前缀改对）

`ai-multiagent/src/main/resources/application.yml` —— 现在这一段（约第 74 行）：

```yaml
  multiagent:                    # ← 前缀错了，GraphProperties 读的是 ai.graph.*
    assistant-id: ai-multiagent
    checkpoint-ttl-hours: 24
```

改成：

```yaml
  graph:                         # ← 只改这一个词（40 号的问题 3）
    # 长期记忆的归属标识，和 assistant-demo 的不同
    assistant-id: ai-multiagent
    # checkpoint 的 TTL（小时）
    checkpoint-ttl-hours: 24
    # 审批阈值：达到这个危险等级的工具需要人工批准
    approval-min-risk: high
    # ─── ★ 本单新增 ───
    # 一次请求里专家最多决策几步
    max-steps: 8
    # 同一工具 + 完全相同的参数，连续处理几次就熔断
    max-repeat: 3
    # 喂给模型的历史消息条数上限
    max-history-messages: 20
    # 单个工具结果进上下文的字符上限
    max-tool-result-chars: 4000
    # langgraph4j 兜底上限（按超步计，要 ≥ 3 × max-steps）
    recursion-limit: 50
```

> `ai.mcp.servers.*.headers.Authorization` 那段的明文 token **本单不动**
> （理由见 40 号第 6 节）。**但注意：它已经进了 git 历史，
> 换环境变量这件事的触发点是「这个仓库要被第二个人 clone，或者要进 CI」。**

---

## 6. P1：调度器与容错

### 6.1 `SupervisorNode`（不再污染 `MESSAGES`）

`ai-graph/src/main/java/com/best/ai_graph/node/SupervisorNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.graph.RouteDecider;
import com.best.ai_graph.state.MultiAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

@Slf4j
public class SupervisorNode implements NodeAction<MultiAgentState> {

    private final RouteDecider decider;

    private final String fallbackEdgeKey;

    public SupervisorNode(RouteDecider decider, String fallbackEdgeKey) {
        this.decider = decider;
        this.fallbackEdgeKey = fallbackEdgeKey;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        // 熔断后不再花一次模型调用：直接收尾
        if (state.forceEnd()) {
            log.info("检测到强制收尾标记，调度器直接走 {}", fallbackEdgeKey);
            return Map.of(MultiAgentState.NEXT, fallbackEdgeKey,
                    MultiAgentState.ROUTE_TRACE, fallbackEdgeKey);
        }
        ExpertRouting routing;
        try{
            routing = decider.decide(state.messages());
        } catch (Exception e){
            // ★ 不是所有异常都值得炸掉整张图：枚举解析失败（模型多打了一个引号）
            //   就属于"这一轮判不出来"，走兜底比抛给用户好（41 号 1.5 节的容错边界）
            log.error("调度失败，走兜底节点 {}", fallbackEdgeKey, e);
            return Map.of(MultiAgentState.NEXT, fallbackEdgeKey,
                    MultiAgentState.ROUTE_TRACE, fallbackEdgeKey);
        }
        String edgeKey = routing.edgeKey();
        log.info("调度决定: {} -> {}", routing, edgeKey);
        // ★★ 关键改动：调度痕迹写进 ROUTE_TRACE，不再写进 MESSAGES。
        //    原来那条 AiMessage「(调度：order)」对模型意味着"我自己说过一句没头没尾的话"，
        //    纯噪声，还随轮数累积。
        return Map.of(
                MultiAgentState.NEXT, edgeKey,
                MultiAgentState.ROUTE_TRACE, edgeKey
        );
    }
}
```

> **为什么调度失败的消息也删掉了。** 原来 `(调度失败，直接收尾)` 那条 AiMessage
> 同样会进上下文，模型会以为"我自己说过要收尾了"。**日志里有就够，模型不需要知道。**

### 6.2 `RoutingMenus`（新增，从注解反射生成菜单）

`ai-graph/src/main/java/com/best/ai_graph/graph/RoutingMenus.java`

```java
package com.best.ai_graph.graph;

import dev.langchain4j.model.output.structured.Description;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 从路由枚举反射生成「路由菜单」文本，供调度器的系统提示词使用。
 *
 * <p><b>为什么需要它</b>（41 号 1.5 节实测）：枚举常量上的 {@code @Description}
 * 只在【提示词版结构化输出】下会被带进消息；一旦模型声明支持
 * {@code Capability.RESPONSE_FORMAT_JSON_SCHEMA}，生成出来的 schema 里
 * {@code JsonEnumSchema.description} 是 null —— 模型只看得到几个裸枚举名。
 *
 * <p>所以把描述<b>显式写进系统提示词</b>，并且<b>从同一份注解生成</b>：
 * 提示词里写的和代码里声明的永远一致，换模型也不会漂移。
 *
 * <p>注解确认（javap）：{@code @Description} 的
 * {@code @Target({FIELD, TYPE})} + {@code @Retention(RUNTIME)}，
 * 而枚举常量在字节码里就是一个 {@code public static final} 字段 —— 反射读得到。
 */
public final class RoutingMenus {

    private RoutingMenus() {
    }

    public static <E extends Enum<E> & ExpertRouting> String render(Class<E> routingEnum) {
        List<String> lines = new ArrayList<>();
        for (E routing : routingEnum.getEnumConstants()) {
            String desc = descriptionOf(routing);
            lines.add("- " + routing.edgeKey() + " ：" + (desc.isBlank() ? routing.name() : desc));
        }
        return String.join("\n", lines);
    }

    /** ★ @Description 的 value() 返回 String[]（javap 确认），不是 String */
    private static <E extends Enum<E>> String descriptionOf(E constant) {
        try {
            Field field = constant.getDeclaringClass().getField(constant.name());
            Description d = field.getAnnotation(Description.class);
            return d == null ? "" : String.join(" ", d.value());
        } catch (NoSuchFieldException e) {
            return "";
        }
    }
}
```

> **为什么菜单里用 `edgeKey()`（小写）而不是 `name()`（大写）。**
> 实测枚举解析是**大小写不敏感**的（`order` 和 `ORDER` 都行），
> 而 `edgeKey()` 正好就是路由表的 key，让模型看到的就是它该输出的东西。

### 6.3 `SupervisorAgent`（去掉 `@SystemMessage`）

`ai-multiagent/src/main/java/com/best/multiagent/agent/SupervisorAgent.java`

```java
package com.best.multiagent.agent;

import com.best.ai_graph.expert.Expert;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.multiagent.expert.AnalysisExpert;
import com.best.multiagent.expert.KnowledgeExpert;
import com.best.multiagent.expert.OrderExpert;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    enum Routing implements ExpertRouting {
        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER("订单助手", new OrderExpert()),

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS("数据分析助手", new AnalysisExpert()),

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE("知识库助手", new KnowledgeExpert()),

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH(null, null);

        private final String role;
        private final Expert expert;

        Routing(String role, Expert expert) {
            this.role = role;
            this.expert = expert;
        }

        public String edgeKey(){
            return name().toLowerCase();
        }

        @Override
        public String expertRole() {
            return role;
        }

        @Override
        public Expert expert() {
            return expert;
        }
    }

    /**
     * ★ 去掉了 @SystemMessage —— 系统提示词（含路由菜单）改由
     * MultiAgentGraphConfig 拼好、作为种子的第一条传进来。
     *
     * <p>枚举常量上的 @Description 【保留】：它现在的作用是
     * 「路由菜单的数据源」（RoutingMenus 反射读它），顺便在提示词模式下
     * 让框架自动再带一份描述，不会冲突。
     */
    Routing route(@UserMessage String turnInstruction);
}
```

> 和你现在这份的差别只有两处：**删掉 `@SystemMessage` 那 5 行**，
> 以及 `route` 的参数名 `conversation` → `turnInstruction`（名字而已）。
> **`Routing` 枚举一个字都没动。**

### 6.4 `MultiAgentGraphConfig`（播种式 decider）

`ai-multiagent/src/main/java/com/best/multiagent/config/MultiAgentGraphConfig.java`

```java
package com.best.multiagent.config;

import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.agent.AgentMessages;
import com.best.ai_graph.agent.SeededAgents;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.graph.MultiAgentGraphFactory;
import com.best.ai_graph.graph.RoutingMenus;
import com.best.ai_graph.state.MultiAgentState;
import com.best.multiagent.agent.SupervisorAgent;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    /**
     * 调度器的系统提示词。
     *
     * <p>路由菜单从 Routing 的 @Description 生成（不会和代码漂移）；
     * 判断依据写在这里，不依赖框架会不会把枚举描述带进 schema。
     */
    private static final String SUPERVISOR_SYSTEM = """
            你是任务调度器。根据对话内容，判断下一步该交给谁。

            可用路由（必须原样输出其中一个名字）：
            %s

            判断依据：
            - 结合语义和对话历史选择合适的助手
            - 如果最近几轮里已经有明确的结论或回答，就选 finish
            - 如果最后一条是工具返回结果、并且还没有据此给出结论，不要选 finish
            """.formatted(RoutingMenus.render(SupervisorAgent.Routing.class));

    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            ChatModelRegister chatModelRegister,
            GraphProperties graphProperties,
            MultiAgentGraphFactory graphFactory
    ) throws GraphStateException {
        return graphFactory.createAgent(
                SupervisorAgent.Routing.class,
                // ★ 播种式决策：让调度器看到【真消息列表】，而不是 render 出来的字符串
                messages -> SeededAgents.build(
                        SupervisorAgent.class,
                        chatModelRegister.getDefault(),
                        AgentMessages.seed(SUPERVISOR_SYSTEM, messages, graphProperties.getMaxHistoryMessages())
                ).route("请给出你的路由决策。"),
                SupervisorAgent.Routing.FINISH.edgeKey()
        );
    }
}
```

> **注意 `supervisorAgent` 那个 `@Bean` 被删掉了。** 原来它是单例的
> （一份 `AiServices` 代理共享给所有调用），播种式做法必须每次按新种子重建，
> 所以改成在 decider 里构建。**全项目只有这个文件引用 `SupervisorAgent` 的 bean**，
> 删掉是安全的（`SupervisorAgent.Routing` 这个**枚举**仍被大量引用，不受影响）。

---

## 7. P2：健壮性

### 7.1 步数预算与 `recursionLimit` 的真实关系

`recursionLimit` 计的是 **超步（superstep）数**，不是「专家决策次数」。你这张图一轮
「专家 → 工具」至少消耗 2 个超步，中间还夹调度器，实际一轮约 3 个：

| 配置 | 值 | 意味着 |
|---|---|---|
| `recursionLimit` | 25（原值） | 约 8 轮就撞墙，报 `Maximum number of iterations` |
| `recursionLimit` | 50（本单） | 兜底足够宽，正常永远撞不到 |
| `maxSteps` | 8（本单） | **业务预算**：第 9 次想决策时，输出一句人话收尾 |

**两者分工**：`maxSteps` 负责「体面地停下并告诉用户」，`recursionLimit` 只负责
「框架别真的转飞了」。撞到 `recursionLimit` 是异常路径，撞到 `maxSteps` 是正常路径。

### 7.2 消息窗口与结果截断

`MESSAGES` 是 appender 通道，**只增不减**。多轮之后它会同时撑大三样东西：
发给模型的上下文、Redis 检查点、以及成本。

- **窗口**：`AgentMessages.window(...)` 取尾部 N 条，并且**头部是工具结果就整条丢掉**
  （孤儿 tool 消息 → 网关 400）。这是本单最容易踩的坑。
- **截断**：`ToolExecuteNode.truncate(...)` 把工具结果压到 `maxToolResultChars`。
  被截掉的原文建议落日志（`log.debug`）或对象存储，**别让它进上下文**。

> ⚠️ 窗口大小是有取舍的：`max-history-messages: 20` 意味着**20 条之前的内容模型看不到**。
> 更早的「已确认事实」应该靠 `WriteMemoryNode` 落长期记忆，而不是靠拉长窗口。

### 7.3 检查点与并发

| 项 | 现状 | 本单处理 |
|---|---|---|
| `releaseThread` | 已设 `false` ✓ | 保持（设 `true` 会丢历史、`getState()` 抛 `Missing Checkpoint!`） |
| TTL | 已设 `checkpointTtlHours` ✓ | 保持 |
| 序列化器 | 图/saver **两个实例**（第 65 / 141 行） | 改为共用 `serializer` 字段 ✓ |
| 同 `threadId` 并发 | **无保护** | 见下方补丁（可选） |

**同 threadId 并发**：两个请求同时打同一个 `sessionId`，会互相覆盖检查点、
并基于旧快照播种。最低成本的修法是按 threadId 串行（`AgentController` 里加即可）：

```java
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private <T> T serialized(String threadId, java.util.function.Supplier<T> action) {
        Object lock = LOCKS.computeIfAbsent(threadId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
```

用的时候把 `run(...)` 包一层：`serialized(threadId, () -> run(...))`。

> ★ **这个 `LOCKS` 是"键域无界且不淘汰"的缓存 —— 三个缓存里唯一一个会泄漏的。**
> 详见 7.4 的缓存清单。本单只给这个最小版本够单机开发用；
> 真正上线要换成「Redisson 按 threadId 加锁」或带 TTL 的本地缓存。

### 7.4 全项目的缓存清单（动手前先看这张表）

本单会碰"缓存"这件事（`SeededAgents` 每次新建代理、`LOCKS` 是自建缓存），
所以先把**你项目里已经存在的缓存**列清楚 —— 免得再造一个重复的：

| 缓存 | 位置 | 键域 | 淘汰 | 结论 |
|---|---|---|---|---|
| **ChatModel** | `ModelRegister.cache`（`Map<String, M>` + `computeIfAbsent`） | profile（配置里固定的那几个） | **无** | 键域**有界** → 永不淘汰是对的。三个 Register（chat / streaming / embedding）共用这一个基类 |
| **工具危险等级** | `McpToolCatalog.riskCache` + `riskLoadedAt` | **全表快照**（不是按 key） | **TTL**：`ai.mcp.risk-cache-ttl`，默认 **60s** | ★ 全项目**唯一**一个带 TTL 的**进程内读缓存**（其余的 TTL 都是持久化 TTL：会话 48h / 摘要 96h / 检查点 24h）。它还会**读失败沿用旧快照** —— 这是 DB 抖动时不误判危险等级的设计，别改掉 |
| 会话记忆（dev） | `InMemorySessionMemoryStore` / `InMemorySessionSummaryStore` | memoryId | **无** | 只在 `ai.memory.session.store=memory` 时装配；**你当前 yml 是 `store: redis`，所以这两个不生效** |
| 工具风险 warn-once | `ToolRisk.WARNED`（`newKeySet()`） | risk 原始字符串 | **无** | 键域极小（几个枚举名），安全。这是项目里 **warnOnce** 的既有范式 |
| 会话消息窗口 | `SummarizingChatMemory` 的 `maxMessages` | memoryId | **逐出+摘要**（`add()` 内部） | ★ 逐出**无配对保护** → 会切出孤儿 TOOL 消息，见 1.7 / 7.5。和图这条线无关，别混用 |
| AiServices 代理 | 本单 `SeededAgents` | — | 不缓存（每次新建） | 见 5.5：便宜，因为模型是缓存的 |
| 同会话串行锁 | 本单 7.3 的 `LOCKS` | threadId | **无** | ★ 键域**无界** → 会泄漏。这是全表唯一"键域无界且不淘汰"的一处，必须换掉 |

**两条从这张表里读出来的结论：**

1. **`ModelRegister` 是"永不淘汰"的正面样板** —— 它敢不淘汰，是因为键域
   是**配置里写死的 profile 集合**，上界确定。`LOCKS` 的 key 是 threadId，
   上界是"历史会话总数"，**同一个写法就是 bug**。判断一个缓存能不能不淘汰，
   看的是**键域有没有上界**，不是"别人也这么写"。

2. ⚠️ **`McpToolCatalog.list(clientKey)` 没有缓存。**
   `riskSnapshot()` 缓存了危险等级，但 `list()` 每次都实打实调
   `client.listTools()` —— **一次 MCP 网络往返**。
   而本单 5.7 让 `ExpertNode` **每一轮**都 `toolCatalog.list(clientKey)`
   并把结果渲染进系统提示词。也就是每轮 = 1 次 LLM 调用 + 1 次 `listTools()` RPC。
   **本单不改它**（改成缓存要定失效策略，属独立一件事），
   但你要知道这个成本存在，别在压测时误判成"图太慢"。

> **缓存清单的用法**：以后想加缓存，先在这张表里找有没有同类；
> 有的就沿用同一套淘汰策略（TTL 就给 TTL，warnOnce 就给 warnOnce），
> 别再发明第三种。

### 7.5 ★ 修 `SummarizingChatMemory` 的孤儿 TOOL 消息（4 行）

**这一条不是 `ai-graph` 的问题**，是 `assistant-demo` 那条线的（1.7 节实测已证明）。
放在这里是因为它和 5.4 的窗口裁剪是**同一个坑的两面**，一次修完省事。

`ai-memory/src/main/java/com/best/ai_memory/session/SummarizingChatMemory.java`

先看你现在的样子（第 60-70 行附近）：

```java
    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        String summary = summaryStore.get(id);
        if(summary != null && !summary.isBlank()){
            result.add(SystemMessage.from("[更早的对话摘要]\n" + summary));
        }
        result.addAll(store.getMessages(id));
        return result;
    }
```

改成：

```java
    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        String summary = summaryStore.get(id);
        if(summary != null && !summary.isBlank()){
            result.add(SystemMessage.from("[更早的对话摘要]\n" + summary));
        }
        result.addAll(store.getMessages(id));
        // ★ 头部一旦是工具结果就整条丢掉 —— 孤儿 tool 消息会让 OpenAI 兼容网关直接 400。
        //   成因见 41 号 1.7 节：add() 的头部逐出是"按条数截断"，不认识工具调用这一对。
        while (result.size() > 1 && result.get(1) instanceof ToolExecutionResultMessage) {
            result.remove(1);
        }
        return result;
    }
```

> **为什么下标从 1 开始比较，而不是从 0。** 下标 0 是刚插进去的摘要 `SystemMessage`
> （如果有），工具结果只可能在它**之后**。所以判断 `get(1)`，
> 而 `result.size() > 1` 保证不会越界。
>
> ⚠️ **为什么不用 `AgentMessages.window(...)` 复用这段逻辑。** `ai-graph` 依赖 `ai-memory`，
> 反过来依赖就是循环依赖。两个模块各留一份 4 行的裁剪，**这是故意的重复**。

**验收**（不用起 Spring，直接看行为）：

```bash
# 起应用，用同一个 sessionId 连续问 21 次（max-messages: 20）
# 然后 grep 日志里的 400
grep -c "tool_call_id" logs/*.log     # 期望 0 —— 修之前跨窗口那次必现
```

---

## 8. 验收

### 8.1 编译

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

**期望**：5 个模块全 `SUCCESS`，0 个 ERROR。

### 8.2 离线回归（★ 本单最值钱的三个测试）

先说清楚代价：`ai-graph/src/test` 目录**不存在**，`ai-graph/pom.xml` 里也没有测试依赖。
所以要加一次依赖：

```xml
<!-- ai-graph/pom.xml，加到 <dependencies> 末尾 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

> `spring-boot-starter-test` 带来 junit-jupiter + assertj + mockito。
> 下面三个测试**不启动 Spring、不连 Redis、不连 MCP**，纯 Java，
> 因为被测对象就是「消息这一层」—— 这正是本单的核心。
> （探针里已经证明可行：`ChatModel` 的**所有方法都是 `default`**，写 stub 只覆盖 `doChat` 即可。）

**测试 1：窗口裁剪不会产生孤儿工具消息**

`ai-graph/src/test/java/com/best/ai_graph/agent/AgentMessagesTest.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMessagesTest {

    private static ToolExecutionRequest req(String id) {
        return ToolExecutionRequest.builder().id(id).name("someTool").arguments("{}").build();
    }

    @Test
    void 窗口裁剪后头部不能是孤儿工具结果() {
        List<ChatMessage> all = new ArrayList<>();
        all.add(UserMessage.from("问题1"));
        // 一段工具问答：AI(tool_calls) + TOOL
        all.add(AiMessage.from(req("call_1")));
        all.add(ToolExecutionResultMessage.from(req("call_1"), "结果1"));
        all.add(UserMessage.from("问题2"));
        all.add(AiMessage.from(req("call_2")));
        all.add(ToolExecutionResultMessage.from(req("call_2"), "结果2"));

        // 窗口 = 3 → 原始尾部是 [TOOL(call_2), ...]，必须把开头的孤儿工具结果丢掉
        List<ChatMessage> window = AgentMessages.window(all, 3);

        assertThat(window).isNotEmpty();
        assertThat(window.get(0)).isNotInstanceOf(ToolExecutionResultMessage.class);
    }

    @Test
    void 种子第一条必须是系统提示词() {
        List<ChatMessage> seed = AgentMessages.seed("【角色】", List.of(UserMessage.from("你好")), 20);

        assertThat(seed.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(seed).hasSize(2);
    }

    @Test
    void 不设上限时原样返回() {
        List<ChatMessage> all = List.of(UserMessage.from("只有一条"));
        assertThat(AgentMessages.window(all, 0)).containsExactlyElementsOf(all);
    }
}
```

**测试 2：模型收到的就是真消息列表（本单的核心断言）**

`ai-graph/src/test/java/com/best/ai_graph/agent/ExpertAgentSeedingTest.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertAgentSeedingTest {

    /** 记录"模型实际收到什么"的假模型。★ ChatModel 全是 default 方法，只需覆盖 doChat */
    static class RecordingChatModel implements ChatModel {
        final List<ChatMessage> received = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            received.addAll(request.messages());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"reply\":\"好的\",\"tool\":\"NONE\",\"arguments\":{}}"))
                    .build();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OPEN_AI;
        }
    }

    @Test
    void 工具消息必须原样送到模型_不能拍平成字符串() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1").name("order__search").arguments("{\"id\":1}").build();

        List<ChatMessage> history = List.of(
                UserMessage.from("帮我查订单 1"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "{\"total\":0}"));

        RecordingChatModel model = new RecordingChatModel();
        SeededAgents.build(ExpertAgent.class, model,
                AgentMessages.seed("【角色】", history, 20)).propose("给出下一步决策。");

        // 顺序：SYSTEM → USER → AI(tool_calls) → TOOL → USER(本轮指令)
        assertThat(model.received).hasSize(5);
        assertThat(model.received.get(0)).isInstanceOf(SystemMessage.class);

        AiMessage ai = (AiMessage) model.received.get(2);
        assertThat(ai.hasToolExecutionRequests()).isTrue();

        ToolExecutionResultMessage tool = (ToolExecutionResultMessage) model.received.get(3);
        // ★ 这一条就是"AI 能看到工具结果"的证明
        assertThat(tool.id()).isEqualTo(ai.toolExecutionRequests().get(0).id());
        assertThat(tool.toolName()).isEqualTo("order__search");
    }
}
```

**测试 3：真工具消息能过检查点序列化**

`ai-graph/src/test/java/com/best/ai_graph/state/ToolMessageCheckpointTest.java`

```java
package com.best.ai_graph.state;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMessageCheckpointTest {

    @Test
    void 工具消息能过检查点_并保持_id_配对() throws Exception {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1").name("order__search").arguments("{\"id\":1}").build();

        List<ChatMessage> original = List.of(
                SystemMessage.from("【系统】"),
                UserMessage.from("帮我查订单"),
                AiMessage.from(request),
                ToolExecutionResultMessage.builder().id("call_1").toolName("order__search")
                        .text("{\"total\":0}").isError(false).build());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(MultiAgentState.MESSAGES, original);
        data.put(MultiAgentState.SESSION_ID, "s1");

        StateSerializer<MultiAgentState> ser = new LC4jStateSerializer<>(MultiAgentState::new);
        String stored = ser.writeDataAsString(data);
        MultiAgentState back = ser.readDataFromReader(new StringReader(stored));

        assertThat(back.messages()).isEqualTo(original);

        AiMessage ai = (AiMessage) back.messages().get(2);
        ToolExecutionResultMessage tool = (ToolExecutionResultMessage) back.messages().get(3);
        assertThat(tool.id()).isEqualTo(ai.toolExecutionRequests().get(0).id());
    }
}
```

跑：

```bash
/opt/homebrew/bin/mvn -o -pl ai-graph test
```

**期望**：`Tests run: 5, Failures: 0, Errors: 0`。

> 这一套测试的价值在于：**它把「模型看到什么」从"我猜"变成了可断言的事实**。
> 循环那个 bug 之所以拖了那么多轮，就是因为没有这样的断言。

### 8.3 端到端四条用例

```bash
# ① 普通问答 / 低危工具 → done
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'

# ② 高危工具 → waiting_approval
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'   # → waiting_approval

curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'

# ③ ★ 本单新增验收：拒绝之后不能死转
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r3"}'   # → waiting_approval
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r3","approved":false}'                                    # → 不应再 waiting，应给出结论

# ④ 多轮：第二句必须能看到第一句
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"那这个订单多少钱？","sessionId":"r1"}'
```

### 8.4 日志硬指标

修完之后，一次带工具的对话日志**必须长这样**（这是最直观的自检）：

```
建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH 步数上限=8 重复熔断=3 历史窗口=20
调度决定: ORDER -> order
专家 订单助手 提议工具: order__searchMultipartBizOrder id=call_7f3a... 参数: {"request":{"phones":[...]}}
工具执行成功: tool=order__searchMultipartBizOrder id=call_7f3a... by=order 结果长度=64
专家 订单助手 结论（不调工具）: 这个号码下没有查到订单。
```

**五个必须成立的硬指标：**

| 指标 | 怎么查 | 期望 |
|---|---|---|
| `id=` 前后一致 | 同一次调用里专家的 `id=` 与工具的 `id=` | **完全相同**（改之前工具侧是另一个随机 UUID） |
| 不再出现同参重复 | `grep "提议工具" 日志`，看参数是否重复出现 | 同一参数**最多 `max-repeat` 次** |
| 不再撞框架上限 | `grep "Maximum number of iterations" 日志` | **0 条** |
| 调度次数收敛 | `grep -c "调度决定" 日志` | 一次请求 **≤ 3**（32 号的指标是 ≤2，多一次是熔断收尾） |
| 上下文里没有调度噪声 | `grep "(调度：" 日志` | **0 条**（那一句已经不再写进 `MESSAGES`） |

---

## 9. 自查清单

**消息模型（P0 的核心）**
- [ ] `ExpertNode` 调工具时写的是 `AiMessage.from(ToolExecutionRequest)`，**不是** `AiMessage.from(String)`
- [ ] `ToolExecuteNode` 写的是 `ToolExecutionResultMessage`，且 **id 来自 `state.toolCallId()`（或回捞）**，不再是 `UUID.randomUUID()`
- [ ] `ToolExecuteNode` 的**四个**出口（拒绝 / 无通道 / 成功 / 异常）**全都是工具结果消息**
- [ ] ★ `finishContinue` / `finishDone` **两个方法名还在**，`interrupt` **一行没动**
- [ ] `ExpertNode` 里**没有** `renderConversation` 了（它只剩日志/记忆两个用途）
- [ ] 喂模型走的是 `SeededAgents.build(...)` + `AgentMessages.seed(...)`
- [ ] 系统提示词在**种子的第一条**，`ExpertAgent`/`SupervisorAgent` 上**没有** `@SystemMessage`

**状态与配置**
- [ ] `SCHEMA` 用的是 `Map.ofEntries`（**不是** `Map.of`）
- [ ] `STEP_COUNT` / `REPEAT_COUNT` 是自己 `+1` 写的（`Channels.base` 不累加）
- [ ] `application.yml` 里是 `ai.graph:`（**不是** `ai.multiagent:`）
- [ ] 启动日志里 `步数上限/重复熔断/历史窗口` **显示的是你配的值**（改一个试一次）
- [ ] 图与 saver 用的是**同一个** `serializer` 字段（第 141 行的 `<AgentState>` 那个 `new` 已经没了）

**容错与治理**
- [ ] 幻觉工具名会回一条系统提示（`resolveTool` + `newMessages` 两条）
- [ ] `ExpertNode` 的 catch 写的是 `SystemMessage` + `EXPERT_ERROR=true`
- [ ] `SupervisorNode` 不再往 `MESSAGES` 里写任何东西（只有 `NEXT` 和 `ROUTE_TRACE`）
- [ ] 调度异常有 try/catch 兜底（枚举解析失败不再炸图）
- [ ] `routeAfterTool` 会看 `forceEnd`
- [ ] `SummarizingChatMemory.messages()` 有头部裁剪守卫（7.5）

**验收**
- [ ] 8.2 的三个测试全绿（`mvn -o -pl ai-graph test`）
- [ ] 8.4 的五个硬指标都达标（尤其是 `id=` 前后一致）
- [ ] 8.3 的用例 ③（拒绝后不死转）**真的跑过一次**

---

## 10. 落地顺序

| 批次 | 内容 | 做完之后能验证什么 |
|---|---|---|
| **第 1 批** | 5.1 `GraphProperties` → 5.2 `MultiAgentState` → 5.3~5.5 三个新类 → 5.6 `ExpertAgent` | 编译过（**此时 5.7 还没改，会编译失败 —— 这几步必须连着做**） |
| **第 2 批** | 5.7 `ExpertNode` → 5.8 `ToolExecuteNode` → 5.9 工厂 → 5.10 yml | ★ **循环应该就消失了**：`grep "Maximum number of iterations"` 为 0 |
| **第 3 批** | 6.1~6.4 调度器四件事 | 调度不再污染上下文；枚举解析失败不再炸图 |
| **第 4 批** | 8.2 三个测试 + 7.5 那 4 行 + 7.3 串行锁（可选） | 把"模型看到什么"钉成断言；顺手修掉另一条线的 400 |

> **卡在哪一步就停在哪一步。** 第 2 批做完循环就没了 —— 那是本单 80% 的价值。
> 第 3、4 批是"从能跑到稳"，不要为了它们推迟第 2 批。

---

## 11. 本单不做的事（边界）

| 不做 | 为什么 |
|---|---|
| 把 `ChatMessageTexts` 删掉 | 它还有**三个正当用途**：日志审计、`WriteMemoryNode` 写长期记忆、以及万一某 provider 不支持工具消息时的降级通道 |
| 给 `ExpertNode` 的 `AiServices` 做缓存 | 播种的前提是"每次种子都不同"，缓存的前提是"key 不变" —— 两者互斥。★ **但底层 `ChatModel` 本来就是缓存的**：`chatModelRegister.getDefault()` 走 `ModelRegister` 的 `Map<String, M> cache`（按 profile `computeIfAbsent`），同一个 profile 全程一个实例，每次 `build()` 只新建一层代理。真要动这一层，本项目已有的答案是 `.chatMemoryProvider(...)`（`SummarizingChatMemoryProvider` 就是这个套路，`assistant-demo` 在用），**不是** ThreadLocal（本项目 `ThreadLocal` 只用于 `AiUserContext` 的身份透传） |
| 给 `ai-graph` 补 `langchain4j` 直接依赖 | 它现在靠 `ai-memory` 传递。按 40 号的判据**应该**直接声明，但那是独立一件事，本单不混进来 |
| 让 `ai-graph` 复用 `SummarizingChatMemory` 的裁剪 | 会形成 `ai-memory` ↔ `ai-graph` 循环依赖。7.5 那 4 行是**故意的重复** |
| 给 `McpToolCatalog.list()` 加缓存 | 它每轮一次 `listTools()` RPC（7.4 第 2 条）。要加缓存就得先定"工具清单什么时候算过期"，属独立一件事 |
| 持久化工具结果原文 | 截断掉的原文去哪（日志 / OSS / Redis）是存储策略问题，需要先定保留期与合规口径 |
| 修 `application.yml` 里的明文 token | 同上，40 号第 6 节已留触发点：**当这个仓库要被第二个人 clone、或者要进 CI 的时候** |
| 把「专家失败」上报给调度器做避让 | 本单只把 `EXPERT_ERROR` 写进 state。真正用它做"这个专家本轮别派了"需要改 `RouteDecider` 的签名，属于下一步 |

---

## 12. 复盘：这个 bug 为什么值一整篇文档

**表象是一个死循环，根因是一句话：`ToolExecutionResultMessage` 没有被当成消息。**

回头看 32 号（扁平化 ReAct）当初为什么对：它把"循环"从 supervisor 收进了专家，
少了一半的调度开销。**但它没有动消息的形状** —— 于是循环虽然短了，却变成
"同一个专家、同一个工具、同一组参数，转 25 次"。**结构性改动解决的是"轮次太多"，
不是"模型看不见结果"。**

四件事值得记下来：

1. **「模型看到了什么」是一个可以直接断言的事实，不是玄学。**
   本单 8.2 那三个测试加起来不到 120 行，却能在 3 秒内回答
   "模型到底收到了什么"。这个 bug 之所以耗掉那么多轮对话，
   就是因为我们在**猜**模型看到了什么，而不是**打印**它看到了什么。

2. **框架给的"能跑"和"协议要求的对"是两件事。**
   把工具结果写成 `AiMessage.from(String)` 编译能过、能跑、日志也正常 ——
   **只有模型的行为不对**。这类 bug 没有任何报错可供搜索，唯一的线索是
   "它的输入在语义上重复了"。而发现这一点需要**知道协议长什么样**。

3. **同一个协议坑，在两条线上各有一个入口。**
   `ai-graph` 这边是「工具结果被写成 AiMessage」，`ai-memory` 那边是
   「窗口逐出切出孤儿 tool 消息」（1.7 实测证明了）。**修了一个不代表另一个没了** ——
   它们共用同一条协议约束，却没有任何一处在代码上体现这个约束。
   这就是为什么 5.4 和 7.5 都要留那 4 行裁剪，而**不能**只留一处。

4. **`@Description` 在两种结构化输出模式下行为不同（1.5 节）。**
   这类"换个模型/换个网关就变"的静默漂移，比报错难查得多 ——
   所以本单的解法不是"选对模式"，而是**不依赖框架的传递，把语义显式写进提示词，
   并且从同一份注解生成**（`RoutingMenus`），让漂移在结构上不可能发生。

> **一句话**：39 号把框架抽干净了，40 号把编译修对了，
> **41 号要解决的是"跑起来但答案是错的"** —— 而这一类问题，只有把
> **协议层面的正确性**（消息角色、配对 id、系统提示词的位置、窗口的配对完整性）
> 显式写进代码，才算真的解决。
