# 43 · `ai-orchestrator` 完整模块（12 项能力）

> **本单取代 24~40 号里所有多智能体相关内容**：`ai-graph` / `ai-multiagent` 已确认弃用，
> 那份实现（基于 langgraph4j 的 supervisor 图）的定位与结论见 41/42 号。
> 本模块**不用 langgraph4j**，理由见第 2 节。

**代码状态**：✅ **已直接写入项目**（2026-10-14 用户明确授权"直接写入"）。
6 个模块编译通过（`BUILD SUCCESS`），全类型 Jackson 往返探针 **13/13 通过**（见第 14 节）。
写入前备份了用户的初版到 `/tmp/backup-before-43-final-161835/`。

---

## 0.5 三次修订记录（先读这个，代码已和初版不同）

| 修订 | 内容 | 为什么 |
|---|---|---|
| **44 号** | 角色表从 yml 搬进代码；Controller / 启动类 / yml 搬到 `ai-orchestrator-demo`；框架 pom 摘掉 web / mysql | 提示词是代码资产不是部署配置；框架零业务（39 号判据） |
| **本轮（45 号）** | ① 4 个 core 类 Lombok 化（19 个手写访问器 → `@Getter/@Setter`）② **修掉 3 个 Jackson 断点** ③ `RedisRunStateStore` 改用专用 mapper ④ **`RunReport` 补上 events**（可观测四事件此前"内部有、外部没出口"） | 见下 |

**★ 本轮修掉的 3 个断点，每一个都让"可暂停/可恢复"在运行期断裂，而编译一路绿灯**：

| # | 断点 | 症状 |
|---|---|---|
| 1 | `Budget` / `RunState` / `TaskNode` / `Plan` 的读方法是 **record 风格**（`maxTokens()` / `runId()`），Jackson 不认 | `Budget` 序列化直接抛 `InvalidDefinitionException`；`RunState` 序列化出来只剩 `{"lastDoneCount":0}` → **审批暂停时存进 Redis 的是残骸/空壳** |
| 2 | `TaskNode.isDone()` / `TaskOutcome.isFailed()` 符合 Jackson 的 **is-getter 约定** | 序列化多出 `"done"` / `"failed"` 字段 → 反序列化 `UnrecognizedPropertyException` → 恢复链路断 |
| 3 | `RunEvent` 带 `Instant` 字段，裸 mapper 不注册 `JavaTimeModule` | `InvalidDefinitionException: Java 8 date/time type not supported` |

**修法**：访问器统一 Lombok 标准命名（`getXxx/setXxx`，符合生态约定，任何 ObjectMapper 开箱即对）；
两个 is-getter 加 `@JsonIgnore`（它们是派生属性，`outcome != null` / `status == FAILED` 就是它们的值）；
`RedisRunStateStore` 用**自给自足的专用 mapper**（显式注册 `JavaTimeModule`），
不借用 Spring 注入的那个 —— 跨请求恢复是独立的序列化契约，不能被全局配置悄悄改掉。

**④ 事件没有出口**：`Orchestrator` 到处 `state.addEvent(...)`，事件进了 `state.events`，
但 `report()` 构造 `RunReport` 时没带它 → Controller 响应体里没有任何事件信息；
而运行时一收敛 `store.remove()` 把整个 RunState 删了，**事件连翻 Redis 都翻不到**。
→ `RunReport` 末尾加 `List<RunEvent> events` 组件，`report()` 传 `List.copyOf(events)`
（快照，不是活引用）。RunReport 实时构造、不落库，**加组件零存量成本**。

> ⭐ **这次的教训值得记**：这些 bug **编译全部通过**，只有真跑一次序列化才暴露。
> 交付单里说"编译通过"从来不等于"能跑" —— 所以第 14 节留了往返探针，
> 它是"恢复链路可用"的书面证据，和 `docs/probes/SubGraphProbe.java`（子图方案的否决证据）并列。

---

## 0. 一分钟版

**一句话**：一次动态分解 + 并行执行 + 结构化汇总，全程可中断、可暂停、可恢复。

```java
@RestController
public class Ctrl {
    @PostMapping("/chat")
    public Object chat(@RequestBody Req r) {
        RunOutcome out = orchestrator.run(r.text(), ctx(r.userId(), r.sessionId()));
        return switch (out) {
            case RunOutcome.Completed c -> Map.of("status", "done", "answer", c.answer());
            case RunOutcome.Paused    p -> Map.of("status", "waiting_approval", "runId", p.runId());
            case RunOutcome.Failed    f -> Map.of("status", "failed", "reason", f.reason());
        };
    }
    @PostMapping("/approve")
    public Object approve(@RequestBody Approve a) { /* 取 state → resume */ }
}
```

**12 项能力**（第 5 节逐项给落点）：

| # | 能力 | 落点 |
|---|---|---|
| 1 | 显式 scope 子任务 | `TaskSpec`（目标 / 期望产出 / 边界 / 依赖） |
| 2 | 上下文隔离 | 每个子任务独立 `List<WorkerTurn>` |
| 3 | 子任务内 ReAct | `WorkerExecutor` 的 while（Thought→Act→Observe） |
| 4 | 结构化交接 | `TaskOutcome`（摘要 + 依据 + 置信度 + 未覆盖项） |
| 5 | 循环迭代 | `Orchestrator.refine()` —— 每轮问规划器"还要补什么" |
| 6 | 组合式终止 | `TerminationPolicy`（4 条判据，记录命中哪条） |
| 7 | 预算兜底 + 降级 | `Budget`（剩余 <20% 降并行度，耗尽收敛） |
| 8 | 错误显式标记 | 失败也是 `TaskOutcome`，原样进汇总，不静默吞 |
| 9 | 人在回路 | `PendingApproval`，**循环中间**也能停 |
| 10 | 可暂停 / 可恢复 | `RunState` + `RunStateStore`（内存 + Redis） |
| 11 | 可观测四事件 | `RunEvent`：派发 / 完成 / 收敛 / 异常 |
| 12 | 会话历史接入 | `MemoryAccess`（`ConversationProvider` 接 `ChatMemoryProvider`） |

---

## 1. 模块坐标

```
ai-orchestrator
   ├─ 依赖：ai-common（模型登记 / MCP / token 计量）、ai-memory（记忆）
   ├─ 不依赖：langgraph4j、ai-graph、ai-multiagent
   └─ 对外契约（冻结）：core 包 7 个数据类 + ApprovalGate + RunStateStore
```

**目录**（`src/main/java/com/best/ai_orchestrator/`）：

```
core/            7 个契约数据类 + Budget + Plan + TaskNode + RunOutcome
plan/            Planner（2 个方法：plan / refine）
worker/          WorkerAgent（AiService 接口）+ WorkerExecutor（ReAct 循环 + 审批）
orchestrator/    Orchestrator（唯一控制流）+ Synthesizer + TaskDispatcher + TerminationPolicy
hitl/            ApprovalGate + RunStateStore 接口 + RedisRunStateStore
memory/          MemoryAccess（会话历史 + 长期记忆 + memoryId 唯一构造点）
observability/   RunEvent（四个事件的载体）
config/          OrchestratorProperties + AiOrchestratorAutoConfiguration
controller/      OrchestratorController（可直接跑的 HTTP 出入口）
```

---

## 2. 为什么不用 langgraph4j（41 号调研的落地结论）

1. **需求交集极小**：12 项能力里，图引擎能替代的只有 1 项（暂停/恢复 + 持久化），
   而且那项 `RunStateStore` 自己实现更直接。剩下 11 项它一项都不管。
2. **控制流是一条主循环**：规划 → 循环派发 → 汇总，形状固定。
   图引擎的表达力（任意拓扑 / 条件边 / 子图 / 并行分支）**用不上**。
   判据一句话：**「所有请求形状相同，只是内容不同」→ 那是循环，不是图。**
3. **节点级中断的限制本项目实测过**：25 号踩过 `InterruptibleAction` 拼写陷阱；
   32 号证明子图中断不传播（冒烟测试留在 `docs/probes/`）。
   循环住在方法里，这些坑不存在 —— **中断 = `return`，代码放哪就能停在哪。**
4. **channel + reducer 是为"多节点并发改同一份状态"设计的**，而本设计里
   共享状态只发生一次（汇总阶段）—— 那个抽象用不上，选错 reducer 还是静默 bug。

**代价（诚实列）**：没有可视化调试、改形状要改代码、生态支持弱。
**将来上图的触发条件**（写下来，不用再争）：
**需要「同一份状态，走不同拓扑」时** —— 即每个请求的形状不一样，且形状本身要人能看懂、能被校验。
届时改的是 `Orchestrator` 一个类（约 270 行），契约层（core 包）不用动。

---

## 3. 两层上下文分离（42 号矩阵的落地，也是"上下文隔离"的核心）

| 上下文 | Planner | Worker | Synthesizer |
|---|---|---|---|
| 会话历史（刚才聊了什么） | 看 | **不看** | 看 |
| 长期记忆（用户是谁） | 看 | 看 | 看 |
| 本次请求原文 | 看 | **不看** | 看 |
| 自己子任务的 scope | 看 | 看 | 不看 |
| 自己子任务的工具轮 | 不看 | 看 | 不看 |
| 其他子任务的结论 | 看（汇总后） | **不看** | 看 |

★ **指代消解的责任在 Planner**：用户说「那它什么时候发货」，Planner 看到完整对话，
翻译成「查订单 12345 的预计发货时间」；Worker 只看后者。
**这把「理解对话」和「执行任务」分成两件事**，也是 token 成本的关键
（Worker 是数量最多的调用：每子任务 × 每轮工具，隔离把成本压住了）。

---

## 4. 本次是全新建文件，不涉及替换

26 个 java + 1 个 imports + 1 个 yml **全部新建**，另需改 **2 个已有文件**：

| # | 文件 | 改动 |
|---|---|---|
| 1 | `pom.xml`（父） | `<modules>` 里加 `ai-orchestrator`，**删掉 `ai-graph` 和 `ai-multiagent`** |
| 2 | `ai-multiagent/src/main/resources/application.yml` | 不用动（模块弃用，yml 随模块走） |

**父 pom 改完的完整状态**（按项目纪律，替换类指令必须给"改完什么样"）：

```xml
<modules>
    <module>ai-common</module>
    <module>ai-memory</module>
    <module>assistant-demo</module>
    <module>ai-orchestrator</module>
</modules>
```

---

## 5. 12 项能力的逐项落点

**① 显式 scope** —— `TaskSpec` 六个字段：`id` / `goal`（这个子任务要达成什么）/
`expectedOutput`（期望产出形态）/ `boundaries`（不要做什么）/ `roleName`（用哪个角色）/
`dependsOn`（依赖哪些 id）。**没有 scope 的子任务，执行者只能猜。**

**② 上下文隔离** —— `TaskNode.turns()` 是每个子任务独立的 `List<WorkerTurn>`，
谁也不看谁。42 号诊断 `ai-graph` 的原话就是"三个 expert 共读一份 messages"——
本模块从数据结构上就不存在这个问题。

**③ 子任务内 ReAct** —— `WorkerExecutor` 的 while：
`WorkerOutput.thought`（Thought）→ `tool` + `toolArgs`（Act）→ `toolResult`（Observe）。
每轮把历史渲染进提示词 `{{history}}`。

**④ 结构化交接** —— `TaskOutcome`：`summary` / `evidence`（依据）/ `confidence` /
`uncovered`（未覆盖的子问题）/ `tokensUsed`。**`uncovered` 是循环迭代的输入**：
规划器看到"这里没覆盖到"，才会补子任务。

**⑤ 循环迭代** —— `Orchestrator.drive` 每轮结束后调 `planner.refine(...)`，约 15 行。
**这是本模块与"并行版 prompt chaining"的分界线**（41 号 Anthropic 引文：
orchestrator-workers 与 parallelization 的差别是"subtasks aren't pre-defined"）。
失败时按"不再追加"处理，不让一次 refine 毁掉整轮。

**⑥ 组合式终止** —— `TerminationPolicy` 四条判据：全部完成 / 无新进展 /
轮次上限 / 预算耗尽，`stop()` 时带 `reason`，**记录命中哪条**。
"掐掉"和"知道该在哪停"是两件事：前者是刹车，后者是目的地。

**⑦ 预算兜底** —— `Budget.shouldDowngrade()` 剩余 <20% 时并行度降 1（退化成串行）；
`exhausted()` 时本轮结束就收敛。**降级而不是崩**。

**⑧ 错误显式标记** —— 子任务失败也是 `TaskOutcome`（`isFailed()` + `error()`），
原样进汇总。**41 号反模式清单第一条就是"错误被静默合并"**。

**⑨ 人在回路** —— `WorkerExecutor` 在「工具执行之前」发现需审批 → 写 `PendingApproval`
→ 返回 `Paused`。★ **停下那一刻工具真的没执行**（29 号 javap 结论：
`beforeToolExecution` 是 `Consumer` 只能看，所以工具不能挂给 `AiServices`）。

**⑩ 可暂停 / 可恢复** —— `run` 和 `resume` 走**同一个** `drive`。
`RunStateStore` 两个实现：内存（单机测试）+ Redis（跨重启）。
**store 的存取收在 `Orchestrator` 内部**，调用方拿不到"忘了存"的机会
（33 号手法：把容易漏的责任从"人的纪律"变成"代码结构"）。

**⑪ 可观测四事件** —— `RunEvent`：`dispatched` / `completed` / `converged` / `异常`。
`RunState.events()` 随 state 一起序列化，所以**审批恢复后事件不丢**。

**⑫ 会话历史** —— `MemoryAccess` 内部调 `ChatMemoryProvider`（ai-memory 的
`SummarizingChatMemoryProvider`），**编排器自己写会话历史**，不留给调用方。
漏了的症状（第二轮没有上下文）在单轮测试里完全看不出来。

---

## 6. MCP 工具调用链（29 号结论的复用）

```
worker.turn() → 返回 { tool, toolArgs }        ← 模型只「提议」，不执行
   ↓ ① 校验工具名在清单里（幻觉就写回让它重选）
   ↓ ② riskOf(tool) —— 读 mcp_gateway.tools 表拿危险等级
   ↓ ③ 需审批？→ 是则 return Paused（工具没有执行）
   ↓ ④ McpClientRegister.get(key).executeTool(request)
   ↓ ⑤ resultText → WorkerTurn.toolResult → 下一轮 {{history}}
```

★ **工具不挂给 `AiServices`**（29 号 javap 实测，非推测）：
`.tools(...)` 会当场执行、`beforeToolExecution` 是 `Consumer` 只能看、
事件全是事后的、Guardrail 是文本级的。**模型只提议，执行在编排层。**

★ **"能看到什么"和"能调什么"用同一个 key**：`McpToolCatalog.list(key)` 渲染进提示词，
`McpClientRegister.get(key)` 真正执行。key = MCP server key = 网关分发的 token
→ **越权在网关层就被拦掉，本项目不维护 expert↔工具映射表**。

⚠️ **已知限制**：MCP 的 `executeTool` 不返回 token 用量，所以 `TaskOutcome.tokensUsed`
和 `Budget` 用的是**估算值**（`chars/2`），精确值在 `ai_token_usage` 台账。
**两者不混在一个字段**（04 号规矩：准确值和估算值不能混，否则报表对不了账）。

---

## 7. 文件清单与敲法

按依赖顺序分 6 批。**每批敲完编译一次**，出错只会在最新一批里。

| 批 | 内容 | 文件数 |
|---|---|---|
| 1 | pom + yml + imports + properties | 4 |
| 2 | core 契约层 | 11 |
| 3 | observability + hitl + memory | 5 |
| 4 | plan + worker（Planner / WorkerAgent / WorkerExecutor） | 3 |
| 5 | orchestrator（4 个）+ config + application + controller | 7 |
| 6 | 父 pom + 启动验证 | — |

---

# 第一部分 · 代码（按批次）

## 批次 1 · pom + 配置

### 文件路径：`ai-orchestrator/pom.xml`

> **为什么是这些依赖**：`spring-boot-starter-web`（Controller 要）/ `spring-boot-starter-data-redis`
> （`RedisRunStateStore` 要）/ `lombok` / `mysql-connector-j`（ai-memory 的 MyBatis 要）/
> `langgraph4j-core` **不要**（第 2 节）/ `ai-common` + `ai-memory`。
> ⚠️ 注意 `langchain4j-bom` **必须显式声明**，否则 `langchain4j` 主包没有版本号
> （40 号踩过：只写 artifactId 不写版本会解析失败）。


#### `ai-orchestrator/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.best</groupId>
        <artifactId>ai_platform</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>ai-orchestrator</artifactId>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- 传递带来 ai-common（模型登记 / MCP 目录 / token 计量） -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-memory</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>

        <!-- 暂停状态要能跨重启、跨实例恢复 → 必须有 Redis 实现。
             声明 optional：没有 Redis 的使用方仍然能编译、能跑（退回内存实现） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- RunEvent 带 Instant 字段，RedisRunStateStore 的专用 mapper 必须注册
             JavaTimeModule 才能序列化它 —— 这是恢复链路的一部分，不是可选的。
             版本由父 pom 的 spring-boot-starter-parent 统一管理，不写死。 -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

#### `ai-orchestrator/src/main/resources/application.yml`

> ⚠️ **此文件已不存在** —— yml 已按 44 号搬到 ai-orchestrator-demo（框架是库，不带配置）。代码保留备查，但**不要**再往 ai-orchestrator 里建它。

```java
// （源码见 ai-orchestrator-demo 模块）
```

#### `ai-orchestrator/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```text
com.best.ai_orchestrator.config.AiOrchestratorAutoConfiguration
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/config/OrchestratorProperties.java`

```java
package com.best.ai_orchestrator.config;

import com.best.ai_orchestrator.core.AgentRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排器的配置项。
 *
 * <p>用属性类而不是散落的 {@code @Value}：默认值写在字段上、
 * 一眼能看全有哪些可调项、从 yml 到对象的路径是显式的。
 */
@Data
@ConfigurationProperties(prefix = "ai.orchestrator")
public class OrchestratorProperties {

    /** 暂停状态的 TTL（小时）。<b>暂停状态不是资产</b> —— 配 TTL 避免无限堆积 */
    private long runTtlHours = 24;

    /**
     * 这张图的标识。<b>它是双重标识，重复会静默串号</b>：
     * ① 暂停状态的 key 命名空间；② 长期记忆表的 {@code source_assistant}。
     *
     * <p>留空则取 {@code spring.application.name} —— 服务名唯一时这项可以不配。
     */
    private String assistantId;

    /** 达到或超过这个危险等级的工具，需要人工批准后才执行。不认识的值按 HIGH 处理 */
    private String approvalMinRisk = "high";

    /** 主循环最大轮数。<b>它是刹车，不是目的地</b> */
    private int maxRounds = 6;

    /** 连续多少轮"已完成子任务数没有增加"就收敛 —— 增量式终止判据 */
    private int noProgressLimit = 2;

    /** 同一批最多并行几个子任务。<b>只影响并行度，不影响正确性</b> */
    private int maxParallel = 3;

    /** 单次运行的 token 预算上限。<b>超了就强制收敛，不是报错</b> */
    private int tokenBudget = 200_000;

    /** 单个子任务的 token 上限 */
    private int perTaskTokenBudget = 30_000;

    /** 默认的工具轮次上限（子任务自己可以覆盖） */
    private int maxToolRounds = 5;

    /** 带进 Planner / Synthesizer 的对话历史最多取多少条 */
    private int conversationTurns = 10;
}
```

---

## 批次 2 · core 契约层（11 个）

> **这一层是冻结的**：对外承诺过的形状，改动成本随时间上升。
> 判据还是那条 —— **契约要趁没数据**。
> ⭐ 所有数据类**不引用任何框架类型**（没有 langchain4j、没有 Spring），
> 只放 JDK 类型 + `List` / `Map` / `String`。**这正是将来若上图，
> `RunState` 能直接当图 state 的前提**（虽然控制流仍要重写，那是重构不是切换）。


#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/AgentRole.java`

```java
package com.best.ai_orchestrator.core;

/**
 * 一个「角色」的定义。
 *
 * <p><b>它同时声明三件事，而且这三件事必须一致</b>：
 * 身份（谁）、能力边界（能碰到哪些工具）、行为准则（怎么做）。
 * 一个"数据分析专家"如果手里握着订单工具，它的准则和工具清单就会打架 ——
 * 所以它们放在同一条记录里，不可能漂移。
 *
 * @param name         角色名。会填进提示词的"你是谁"，也是规划器选人的键
 * @param description  给<b>规划器</b>看的"这个角色能干什么"（决定什么时候派给它）。
 *                     ★ 它和 {@code systemPrompt} 是两件事：前者是"别人怎么看它"，
 *                     后者是"它怎么看自己"，不能复用同一个字符串
 * @param systemPrompt 这个角色的<b>工作准则</b>。只写它独有的判断，
 *                     通用输出格式由 {@code WorkerAgent} 的骨架保证，不要重复
 * @param mcpClientKey MCP 客户端 key。★ <b>它决定了这个角色能看到哪些工具</b> ——
 *                     越权在网关那一层就被拦掉了，这里不需要再维护一张映射表
 */
public record AgentRole(String name, String description, String systemPrompt, String mcpClientKey) {
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/TaskSpec.java`

```java
package com.best.ai_orchestrator.core;

import java.util.List;
import java.util.UUID;

/**
 * 一个子任务的规格 —— <b>显式 scope</b>。
 *
 * <p>这是它与"给个模糊指令就派出去"的关键区别。生产经验里最反复出现的失败模式之一：
 * <b>不给 scope 的子任务会互相重复劳动、留下空白、或者跑到完全无关的方向上去。</b>
 * 最极端的一个例子是为一个简单查询起了 50 个执行者。
 *
 * <p><b>四个字段缺一不可</b>：
 * <ul>
 *   <li>{@code goal} —— 做什么。<b>它必须是消解过指代的完整描述</b>
 *       （"那它什么时候发货"要由规划器翻成"查订单 12345 的预计发货时间"）</li>
 *   <li>{@code expectedOutput} —— <b>什么样算做完</b>。没有它，执行者不知道什么时候停</li>
 *   <li>{@code boundaries} —— 不要做什么。没有它，执行者会越界</li>
 *   <li>{@code dependsOn} —— 等谁先做完。<b>只有真的要用前一个产出时才写</b>；
 *       写多了会把本来可并行的子任务串起来，白等</li>
 * </ul>
 *
 * @param maxToolRounds 工具轮次上限。<b>上限是刹车，不是目的地</b> ——
 *                      它保证任何逻辑漏洞都不会让某个子任务无限转下去
 */
public record TaskSpec(String id,
                       String goal,
                       String expectedOutput,
                       String boundaries,
                       String roleName,
                       List<String> dependsOn,
                       int maxToolRounds) {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    public TaskSpec {
        if (id == null || id.isBlank()) {
            id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        }
        dependsOn = (dependsOn == null || dependsOn.isEmpty()) ? List.of() : List.copyOf(dependsOn);
        maxToolRounds = maxToolRounds <= 0 ? DEFAULT_MAX_TOOL_ROUNDS : maxToolRounds;
    }

    /** 渲染成给执行者看的"本次任务"段落 */
    public String render() {
        String b = (boundaries == null || boundaries.isBlank()) ? "(无特别限制)" : boundaries;
        return """
                - 目标：%s
                - 期望产出：%s
                - 边界：%s
                """.formatted(goal, expectedOutput, b);
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/TaskOutcome.java`

```java
package com.best.ai_orchestrator.core;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Locale;

/**
 * <b>交接协议</b> —— 执行者交回给编排器的东西。
 *
 * <p>这个类是"多智能体能不能用"的分水岭。三条来自生产实践的要求都落在这里：
 *
 * <ol>
 *   <li><b>浓缩，不回传原文。</b> 执行者读了 40 篇文档，交回的是"结论 + 依据位置"，
 *       不是 40 篇全文。不这么做，编排器的上下文会被子任务撑爆 ——
 *       而"撑爆"是这套架构最常见的死法。</li>
 *
 *   <li><b>必须带 {@code confidence} 和 {@code uncovered}。</b>
 *       编排器靠这两个字段判断"哪里还需要补一轮"（循环迭代就靠它）。
 *       没有它们，编排器只能猜 —— 于是它要么过度再派一轮（烧钱），
 *       要么漏掉缺口（答案不全）。</li>
 *
 *   <li>★ <b>失败必须显式标记，不能静默合并。</b>
 *       {@code status=FAILED} 时 {@code error} 必填，而且这条结果会被
 *       <b>原样带进最终汇总</b>（{@code Synthesizer} 的提示词里明确要求"未完成的部分必须告诉用户"）。
 *       静默合并的后果是：答案看起来完整，但缺了一块，而且没人知道。</li>
 * </ol>
 */
public record TaskOutcome(String taskId,
                          TaskStatus status,
                          String summary,
                          List<String> evidence,
                          double confidence,
                          List<String> uncovered,
                          String error,
                          int tokensUsed) {

    public enum TaskStatus {
        /** 做完了，summary 有效 */
        DONE,
        /** <b>没做完</b>。error 必填，summary 为空 */
        FAILED
    }

    public TaskOutcome {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static TaskOutcome done(String taskId, String summary, List<String> evidence,
                                   double confidence, List<String> uncovered, int tokensUsed) {
        return new TaskOutcome(taskId, TaskStatus.DONE, summary, evidence, confidence, uncovered, null, tokensUsed);
    }

    /** ★ 失败也产生一条 outcome —— 而不是被 catch 吞掉 */
    public static TaskOutcome failed(String taskId, String error, int tokensUsed) {
        return new TaskOutcome(taskId, TaskStatus.FAILED, "", List.of(), 0.0, List.of(), error, tokensUsed);
    }

    /**
     * 是否失败。<b>派生属性，不参与序列化</b> —— 它的值就是 {@code status == FAILED}。
     *
     * <p>⚠️ 必须加 {@code @JsonIgnore}：{@code isXxx} 符合 Jackson 的 is-getter 约定，
     * 不忽略的话序列化会多出一个 {@code "failed"} 字段，反序列化 record 时
     * 找不到对应参数 → {@code UnrecognizedPropertyException}。
     * （{@link TaskNode#isDone()} 同款问题，两个都是实测抓出来的。）
     */
    @JsonIgnore
    public boolean isFailed() {
        return status == TaskStatus.FAILED;
    }

    /** 给汇总器和规划器看的渲染。失败的那条会被明确标出来，不会被混在正文里。 */
    public String render() {
        if (isFailed()) {
            return "- [%s] 未完成：%s".formatted(taskId, error);
        }
        StringBuilder sb = new StringBuilder("- [%s] %s".formatted(taskId, summary));
        if (!evidence.isEmpty()) {
            sb.append("\n  依据：").append(String.join("；", evidence));
        }
        if (!uncovered.isEmpty()) {
            sb.append("\n  未覆盖：").append(String.join("；", uncovered));
        }
        sb.append("\n  置信度：").append(String.format(Locale.ROOT, "%.2f", confidence));
        return sb.toString();
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/WorkerTurn.java`

```java
package com.best.ai_orchestrator.core;

/**
 * 执行者在自己上下文里的一"轮"。
 *
 * <p><b>它是上下文隔离的载体</b>：每个子任务有自己的一份 {@code List<WorkerTurn>}，
 * 互相看不见。编排器最终只看得到浓缩出的 {@link TaskOutcome}。
 *
 * <p><b>为什么存成结构化记录，而不是拼好的字符串</b>：
 * <ol>
 *   <li>要能序列化 —— 暂停 / 恢复靠它（拼好的字符串没法选择性回放）</li>
 *   <li>要能选择性渲染 —— 比如只回放工具结果、丢落款；或者长会话时只保留最近 N 轮</li>
 *   <li>要能统计 —— "这个子任务调了几次工具"是个可观测指标</li>
 * </ol>
 */
public record WorkerTurn(TurnKind kind, String content, String tool, String args) {

    public enum TurnKind {
        /** 执行者给自己的推理（可选，主要给排查用） */
        THOUGHT,
        /** 提议调用某个工具 */
        TOOL_CALL,
        /** 工具返回（或"人工拒绝"这类合成结果） */
        TOOL_RESULT
    }

    public static WorkerTurn thought(String content) {
        return new WorkerTurn(TurnKind.THOUGHT, content, null, null);
    }

    public static WorkerTurn toolCall(String tool, String args) {
        return new WorkerTurn(TurnKind.TOOL_CALL, null, tool, args);
    }

    public static WorkerTurn toolResult(String content) {
        return new WorkerTurn(TurnKind.TOOL_RESULT, content, null, null);
    }

    public String render() {
        return switch (kind) {
            case THOUGHT -> "[思考] " + safe(content);
            case TOOL_CALL -> "[已调用工具] " + tool + "  参数=" + safe(args);
            case TOOL_RESULT -> "[工具返回] " + safe(content);
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/PendingApproval.java`

```java
package com.best.ai_orchestrator.core;

import com.best.ai_common.mcp.ToolRisk;

/**
 * 一个挂起的审批请求。
 *
 * <p><b>它是"暂停"的全部内容</b>：编排器把这个对象交给上层，上层拿它去问人；
 * 人答完之后，把 {@code approved} 传回来继续。
 *
 * <p>刻意带上 {@code taskGoal} —— 光看工具名和参数（比如
 * {@code mysql__mysql_query {"sql":"DELETE FROM ..."}}）人很难判断"该不该批"；
 * 加上"这是在为哪个目标服务"，审批才有信息基础。
 */
public record PendingApproval(String taskId,
                              String taskGoal,
                              String tool,
                              String args,
                              ToolRisk risk,
                              String reason) {
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/TaskNode.java`

```java
package com.best.ai_orchestrator.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个子任务的完整状态：规格 + 私有上下文 + 结果 + 待审批 + 人的决定。
 *
 * <p><b>它必须可序列化</b>（无参构造 + setter）—— 暂停时整个 {@link RunState}
 * 要能存下来，"恢复时从一半继续"靠的就是这里的 {@code turns}。
 *
 * <p>⚠️ <b>为什么是 class + setter 而不是 record</b>：
 * record 的字段不可变，而这里的 {@code turns} 是<b>就地追加</b>的；
 * 而且 Jackson 反序列化 record 需要 {@code @JsonCreator} 或 {@code ParameterNamesModule}。
 * 判据始终是<b>"谁要写它"</b>：反序列化器要写，就用 class + setter。
 *
 * <p>★ <b>访问器用 Lombok 生成标准命名</b> —— 这个对象要进 Redis，
 * Jackson 只认 getXxx/setXxx（record 风格的 {@code spec()} 它不认，
 * 详见 {@link Budget} 类注释里那次事故）。
 */
@Getter
@Setter
public class TaskNode {

    private TaskSpec spec;

    /** <b>这个子任务私有的上下文</b> —— 别的子任务看不见 */
    private List<WorkerTurn> turns = new ArrayList<>();

    private TaskOutcome outcome;

    /** 非 null 表示这个子任务卡在人工审批上 */
    private PendingApproval pending;

    /** 人对 {@link #pending} 的回答：null = 还没答 */
    private Boolean approved;

    public TaskNode() {
    }

    public TaskNode(TaskSpec spec) {
        this.spec = spec;
    }

    /**
     * 是否已完成。<b>派生属性，不参与序列化</b> —— 它的值就是 {@code outcome != null}，
     * 存下来反而多一处可能不一致的真相。
     *
     * <p>⚠️ 必须加 {@code @JsonIgnore}：{@code isXxx} 符合 Jackson 的 is-getter 约定，
     * 不忽略的话序列化会多出一个 {@code "done"} 字段，反序列化时找不到对应的
     * {@code setDone} → {@code UnrecognizedPropertyException}，整条恢复链路断在这里。
     */
    @JsonIgnore
    public boolean isDone() {
        return outcome != null;
    }

    public void addTurn(WorkerTurn turn) {
        turns.add(turn);
    }

    public void finish(TaskOutcome o) {
        this.outcome = o;
        clearDecision();
    }

    public void awaitApproval(PendingApproval p) {
        this.pending = p;
        this.approved = null;
    }

    /** 记下人的回答。恢复时调用 —— 注意它<b>不改</b> pending，由执行器结算后清除 */
    public void decide(boolean ok) {
        this.approved = ok;
    }

    public void clearDecision() {
        this.pending = null;
        this.approved = null;
    }

    /** 渲染私有上下文，给执行者看 */
    public String renderHistory() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkerTurn t : turns) {
            sb.append(t.render()).append('\n');
        }
        return sb.toString();
    }

    // ---- Jackson ----
    // 只有 setTurns 手写：它要 null 保护（反序列化进 null 会让 addTurn NPE）。
    // Lombok 检测到已存在同名 setter 会跳过这个字段，其余 setter 自动生成。

    public void setTurns(List<WorkerTurn> turns) {
        this.turns = turns == null ? new ArrayList<>() : turns;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/Plan.java`

```java
package com.best.ai_orchestrator.core;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 计划：子任务集合 + 依赖关系 + 执行进度。
 *
 * <p><b>依赖关系唯一的作用是决定"哪些可以同时跑"</b> ——
 * 它是并行的<b>前置条件</b>，不是流程图。<b>没有依赖的子任务才会被放进同一批。</b>
 *
 * <p>这也是对 Cognition 那条批评的正面回应：他们的反例（两个子任务画风不一致）
 * 之所以失败，是因为两个子任务<b>实际上互相依赖</b>，却被当成独立的并行了出去。
 * 这里的做法是：<b>依赖关系必须显式写出来</b>，写不出来的说明它们本来就该串起来。
 *
 * <p>★ <b>{@link #add(List)} 支撑循环迭代</b>：编排者看完已有结论，可以追加新的子任务。
 * 这是本模块与"并行版 prompt chaining"的分界线。
 */
@Slf4j
@Getter
@Setter
public class Plan {

    private List<TaskNode> nodes = new ArrayList<>();

    public Plan() {
    }

    public Plan(List<TaskNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    /**
     * nodes 的 setter。<b>手写而不用 Lombok 生成</b> —— 要 null 保护
     * （反序列化进 null 会让 {@code nextBatch} 直接 NPE）。
     */
    public void setNodes(List<TaskNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    /**
     * 追加子任务（循环迭代用）。
     *
     * <p><b>id 已存在的直接跳过</b>，而不是覆盖或改名：
     * <ul>
     *   <li>覆盖 → 会丢掉那个子任务已经做完的结果</li>
     *   <li>改名 → {@code dependsOn} 会指向一个不存在的 id</li>
     * </ul>
     * 两种错都比"少加一个子任务"严重，所以选最保守的那个并打一条 warn。
     *
     * @return 真正加进去的数量
     */
    public int add(List<TaskSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (TaskSpec spec : specs) {
            if (find(spec.id()).isPresent()) {
                log.warn("追加的子任务 id={} 已存在，跳过（覆盖会丢结果，改名会断依赖）", spec.id());
                continue;
            }
            nodes.add(new TaskNode(spec));
            added++;
        }
        if (added > 0) {
            log.info("循环迭代：追加了 {} 个子任务", added);
        }
        return added;
    }

    private Optional<TaskNode> find(String id) {
        return nodes.stream().filter(n -> n.getSpec().id().equals(id)).findFirst();
    }

    /**
     * 依赖是否已满足。
     *
     * <p>依赖<b>不存在</b>时视为满足 —— 规划器偶尔会编一个不存在的依赖 id，
     * 那种情况下"当成满足"最坏是顺序乱了，而"当成不满足"会让整个计划死锁。
     * <b>两种错的代价不对称，所以选前者并打一条 warn。</b>
     */
    private boolean dependencySatisfied(String depId) {
        Optional<TaskNode> dep = find(depId);
        if (dep.isEmpty()) {
            log.warn("子任务依赖了一个不存在的 id: {} —— 按已满足处理，避免死锁", depId);
            return true;
        }
        return dep.get().isDone();
    }

    /** 下一批可以执行（自己没做完、依赖全部满足）的子任务 */
    public List<TaskNode> nextBatch(int maxParallel) {
        List<TaskNode> ready = new ArrayList<>();
        for (TaskNode n : nodes) {
            if (n.isDone()) {
                continue;
            }
            if (n.getSpec().dependsOn().stream().allMatch(this::dependencySatisfied)) {
                ready.add(n);
                if (ready.size() >= Math.max(1, maxParallel)) {
                    break;
                }
            }
        }
        return ready;
    }

    public boolean allDone() {
        return nodes.stream().allMatch(TaskNode::isDone);
    }

    public int doneCount() {
        return (int) nodes.stream().filter(TaskNode::isDone).count();
    }

    public int size() {
        return nodes.size();
    }

    /** 所有已产出的结果，按计划顺序 —— 汇总器和规划器的输入 */
    public List<TaskOutcome> outcomes() {
        return nodes.stream().map(TaskNode::getOutcome).filter(Objects::nonNull).toList();
    }

    /** 首尾相接的浓缩结论。★ 循环迭代和汇总都靠它 */
    public String renderOutcomes() {
        List<TaskOutcome> list = outcomes();
        if (list.isEmpty()) {
            return "(还没有任何子任务产出)";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskOutcome o : list) {
            sb.append(o.render()).append('\n');
        }
        return sb.toString();
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/Budget.java`

```java
package com.best.ai_orchestrator.core;

import lombok.Getter;
import lombok.Setter;

/**
 * Token 预算 —— <b>终止条件的最后一道兜底</b>。
 *
 * <p>多智能体的成本是单次对话的十几倍，所以"花多少"必须是<b>一等公民</b>，
 * 而不是跑完再看账单。这个类只做三件事：记账、判超、给降级信号。
 *
 * <p><b>它算的是估算值，不是精确值。</b> 精确用量在 {@code ai_token_usage} 台账里
 * （由 SDK 层的监听器写）。两者<b>不能混在一张表里</b> ——
 * 估算值的存在意义是"及时刹车"，不是"对账"。
 *
 * <p>⚠️ <b>getter/setter 用 Lombok 生成，而且是标准命名（getXxx/setXxx）</b> ——
 * 这个类要被 Jackson 序列化进 Redis，而 Jackson 只认标准命名。
 * 曾经手写过 record 风格的 {@code maxTokens()} 读方法，结果序列化直接抛
 * {@code InvalidDefinitionException: No serializer found}：
 * 编译完全通过，运行到"暂停存状态"那一刻才炸。
 * ★ <b>判据：一个类只要会被序列化，它的访问器就必须符合生态约定，
 * 不能自创风格。</b>
 */
@Getter
@Setter
public final class Budget {

    private int maxTokens;
    private int perTaskTokens;
    private int usedTokens;

    public Budget() {
    }

    public Budget(int maxTokens, int perTaskTokens) {
        this(maxTokens, perTaskTokens, 0);
    }

    public Budget(int maxTokens, int perTaskTokens, int usedTokens) {
        this.maxTokens = Math.max(maxTokens, 1);
        this.perTaskTokens = Math.max(perTaskTokens, 1);
        this.usedTokens = Math.max(usedTokens, 0);
    }

    public void consume(int tokens) {
        usedTokens += Math.max(tokens, 0);
    }

    public int remaining() {
        return Math.max(maxTokens - usedTokens, 0);
    }

    public boolean exhausted() {
        return usedTokens >= maxTokens;
    }

    /**
     * 是否进入降级区（剩余不足 20%）。
     *
     * <p>编排器据此把下一批的并行度收小 —— <b>而不是等到耗尽才反应</b>。
     * 资源感知优化的要点是"边跑边调"，不是"跑完了发现超了"。
     */
    public boolean shouldDowngrade() {
        return remaining() * 5 <= maxTokens;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/RunState.java`

```java
package com.best.ai_orchestrator.core;

import com.best.ai_orchestrator.observability.RunEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次运行的全部状态 —— <b>它是"暂停 / 恢复"能成立的全部原因</b>。
 *
 * <p><b>为什么这套东西不需要图引擎。</b> 编排器是一个普通 Java 方法，
 * 它可以在主循环的任何一个点上识别出"这次要停下来问人"，然后 {@code return}。
 * 把这个状态对象存下来，恢复就是"再调一次编排器，让它从这里继续"。
 *
 * <p>图引擎提供的是"<b>任意拓扑</b>的条件中断 + 检查点"。
 * 而这里需要的只是"<b>一条主循环上的</b>条件中断" ——
 * 那就用一个可序列化的状态对象解决，不必引入一个框架，
 * 也不必为它付出"状态机 / 顶点 / 边 / 编译期图校验"的全部复杂度。
 *
 * <p>⚠️ <b>为了能存 Redis，这里刻意是 POJO + 无参构造 + setter</b>。
 * ★ <b>访问器必须用标准命名（getXxx/setXxx）</b> —— Jackson 只认这个。
 * 曾手写 record 风格的 {@code runId()} 读方法，序列化出来只剩
 * {@code {"lastDoneCount":0}}（唯一一个标准命名的 getter），其余 11 个字段全丢；
 * 嵌套的 {@link Budget} 更是直接抛 {@code InvalidDefinitionException}。
 * <b>症状：审批暂停后存进 Redis 的是残骸，恢复回来是个空壳</b> ——
 * 而编译一路绿灯。现在用 Lombok 生成标准访问器，并按生态约定来。
 */
@Getter
@Setter
public class RunState {

    private String runId;
    private String request;
    private RunContext context;
    private Budget budget;
    private Plan plan;
    private List<RunEvent> events = new ArrayList<>();

    /** 主循环已经转了几轮 */
    private int rounds;

    /** 连续多少轮"已完成数没有增加" —— 增量式终止判定的输入 */
    private int noProgressRounds;
    private int lastDoneCount;

    private String terminationReason;

    public RunState() {
    }

    public RunState(String runId, String request, RunContext context, Budget budget) {
        this.runId = runId;
        this.request = request;
        this.context = context;
        this.budget = budget;
    }

    /**
     * 一次运行的"外部事实" —— 整条链路上不变，且不来自模型。
     *
     * @param memoryText       长期记忆召回（用户是谁）。<b>只召一次</b>，
     *                         所有子任务共用 —— 它属于"用户是谁"，
     *                         不属于"某个子任务"
     * @param conversationText 会话历史（这次对话刚才聊了什么），渲染后的文本。
     *                         ★ <b>只给 Planner 和 Synthesizer 看，
     *                         不给 Worker 看</b> —— 后者只看自己子任务的 scope。
     *                         这正是上下文隔离：指代消解的责任在规划器，
     *                         执行者拿到的是已经消解好的完整描述
     */
    public record RunContext(String userId,
                             String sessionId,
                             String assistantId,
                             String memoryText,
                             String conversationText) {

        public static RunContext of(String userId, String sessionId, String assistantId) {
            return new RunContext(userId, sessionId, assistantId, "", "");
        }
    }

    // ---- 业务方法（非访问器，Lombok 不生成） ----

    public void beginRound() {
        rounds++;
    }

    /** 每轮结束时调用：更新"有没有新进展"。★ 这是增量式终止判定的输入 */
    public void endRound() {
        int now = plan == null ? 0 : plan.doneCount();
        if (now == lastDoneCount) {
            noProgressRounds++;
        } else {
            noProgressRounds = 0;
            lastDoneCount = now;
        }
    }

    public void addEvent(RunEvent event) {
        events.add(event);
    }

    public RunReport report() {
        int total = plan == null ? 0 : plan.size();
        int done = plan == null ? 0 : plan.doneCount();
        int failed = plan == null ? 0 : (int) plan.outcomes().stream().filter(TaskOutcome::isFailed).count();
        return new RunReport(runId, rounds, total, done, failed,
                budget == null ? 0 : budget.getUsedTokens(),
                budget == null ? 0 : budget.getMaxTokens(),
                terminationReason,
                // ★ 事件必须随报告出去 —— 否则"可观测四事件"只有内部有、外部没出口：
                //   运行时一收敛 RunState 就被删掉，事件连翻 Redis 都翻不到。
                //   防御性拷贝：交出去的东西不该是 state 里的活引用（33 号同款思维）。
                List.copyOf(events));
    }

    /**
     * 运行报告 —— 对外只需要这几个数字 + 事件流，不需要把整个 state 交出去。
     *
     * <p>⚠️ {@code events} 加在**最后一位**：RunReport 是实时构造的、不落库，
     * 所以现在加组件零存量成本；但即便如此，新组件也只加在末尾 ——
     * 中间插一个字段会让所有按位置读它的代码静默错位。
     */
    public record RunReport(String runId,
                            int rounds,
                            int tasksTotal,
                            int tasksDone,
                            int tasksFailed,
                            int tokensUsed,
                            int tokenBudget,
                            String terminationReason,
                            List<RunEvent> events) {
    }

    /**
     * 事件列表的 setter。<b>手写而不用 Lombok 生成</b> ——
     * Lombok 生成的是裸赋值，反序列化进 null 会让后续 {@code addEvent} NPE；
     * 这里兜底成空列表。
     */
    public void setEvents(List<RunEvent> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/core/RunOutcome.java`

```java
package com.best.ai_orchestrator.core;

/**
 * 一次运行的结局 —— <b>sealed，三个分支穷尽</b>。
 *
 * <p><b>它的形状就是"为什么不需要图引擎"的答案。</b>
 * 编排器只有一个出口，而出口有三种：
 * <ul>
 *   <li>{@link Completed} —— 收敛了，有答案</li>
 *   <li>{@link Paused} —— <b>停下等人批准，把整个 state 一并交出去</b>。
 *       调用方负责把它存起来（内存 / Redis / 数据库都行），人答完之后
 *       再用同一个 state 调 {@code resume}</li>
 *   <li>{@link Failed} —— 还没能进入执行就失败了（规划器连续出错）</li>
 * </ul>
 *
 * <p>用 sealed interface 而不是"一个带 status 字段的类"，是为了让
 * <b>调用方必须处理"暂停"这一支</b> —— 漏了它编译器会报错。
 * 而漏了它的后果是"前端看到 done，实际活没干"（这个坑本项目踩过）。
 */
public sealed interface RunOutcome {

    String runId();

    /** 收敛。answer 是最终答案（未完成的子任务已在其中被标注） */
    record Completed(String runId, String answer, RunState.RunReport report) implements RunOutcome {
    }

    /** <b>停下等人批准。</b> state 必须由调用方持久化，恢复时原样交回 */
    record Paused(String runId, PendingApproval pending, RunState state, RunState.RunReport report)
            implements RunOutcome {
    }

    /** 还没能进入执行就失败了。<b>不是子任务失败</b> —— 那种情况走 Completed */
    record Failed(String runId, String error, RunState.RunReport report) implements RunOutcome {
    }
}
```

---

## 批次 3 · 接缝层（observability + hitl + memory）

> **`ApprovalGate` 和 `RunStateStore` 是两个接缝** —— 接口在本模块，实现可以是任何东西。
> `RunStateStore` 现在有两个实现：内存（默认）+ Redis（跨重启，正式模块必需）。
> ⭐ `MemoryAccess.memoryIdOf` 是 memoryId 的**唯一构造点** —— 不手拼。
> 31/33 号两次栽在 `::` 上（少一个冒号 → 长期记忆静默失效），
> 根因都是"手拼字符串"，解法只有"收口到一处"。


#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/observability/RunEvent.java`

```java
package com.best.ai_orchestrator.observability;

import java.time.Instant;
import java.util.Locale;

/**
 * 运行事件 —— <b>四类，构成一条完整的因果链</b>。
 *
 * <p>"这个答案为什么长这样"这个问题，只能靠这四类事件回答。
 * 缺任何一类，出事时你就得靠猜：
 *
 * <ul>
 *   <li>{@code TASK_DISPATCHED} —— 派给了谁、scope 是什么</li>
 *   <li>{@code TASK_COMPLETED} —— 花了多少 token、置信度多少、摘要是什么</li>
 *   <li>{@code LOOP_CONVERGED} —— <b>为什么停</b>、这一轮有没有增量</li>
 *   <li>{@code RUN_FAILED} —— 哪一步坏了、错误原文</li>
 * </ul>
 *
 * <p><b>为什么单独一个包。</b> 事件是"对外承诺"的一部分 ——
 * 将来接 OpenTelemetry / 落盘 / 推流，改的是这一个包，不动编排逻辑。
 * 如果把它散在各处 {@code log.info}，那就永远只能靠 grep 日志。
 */
public record RunEvent(Instant at, Kind kind, String taskId, String detail, int tokens) {

    public enum Kind {
        TASK_DISPATCHED,
        TASK_COMPLETED,
        LOOP_CONVERGED,
        RUN_FAILED
    }

    public static RunEvent dispatched(String taskId, String scope) {
        return new RunEvent(Instant.now(), Kind.TASK_DISPATCHED, taskId, scope, 0);
    }

    public static RunEvent completed(String taskId, String summary, double confidence, int tokens) {
        return new RunEvent(Instant.now(), Kind.TASK_COMPLETED, taskId,
                "置信度=" + String.format(Locale.ROOT, "%.2f", confidence) + " 摘要=" + summary, tokens);
    }

    public static RunEvent converged(String reason, int doneCount, int total) {
        return new RunEvent(Instant.now(), Kind.LOOP_CONVERGED, null,
                reason + "（已完成 " + doneCount + "/" + total + "）", 0);
    }

    public static RunEvent failed(String taskId, String error) {
        return new RunEvent(Instant.now(), Kind.RUN_FAILED, taskId, error, 0);
    }

    public String render() {
        return "%s [%s] %s".formatted(at, kind, detail) + (taskId == null ? "" : " @" + taskId);
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/hitl/ApprovalGate.java`

```java
package com.best.ai_orchestrator.hitl;

import com.best.ai_common.mcp.ToolRisk;

/**
 * 审批网关 —— <b>决定"哪些工具调用必须先问过人"</b>。
 *
 * <p><b>它是"人在回路"的落点，也是最容易被做错的地方。</b>
 * 做错的方式只有一种：<b>先执行、再问要不要批准</b>。
 * 那样审批就成了摆设 —— 因为工具已经跑了。
 *
 * <p>本模块的做法是把判断放在<b>提议之后、执行之前</b>，见
 * {@code WorkerExecutor} 的循环：一旦判定要审批，
 * <b>立刻返回、不执行，并把整个 RunState 交出去</b>。
 *
 * <p>做成接口（而不是写死阈值）是为了三件事都留出余地：
 * 按阈值批、按参数批（"删 3 天数据"和"删全部数据"是同一个工具但风险不同）、
 * 或者干脆接一个外部审批系统。
 */
public interface ApprovalGate {

    /**
     * @param risk 这个工具的静态危险等级（来自 {@code mcp_gateway.tools.risk}）
     * @return true 表示这次调用必须停下等人
     */
    boolean requiresApproval(ToolRisk risk);

    /** 默认实现：达到或超过阈值就要批准 */
    static ApprovalGate threshold(ToolRisk minRisk) {
        return risk -> risk != null && risk.atLeast(minRisk);
    }

    /** 全部放行 —— <b>只该在本地调试用</b> */
    static ApprovalGate open() {
        return risk -> false;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/hitl/RunStateStore.java`

```java
package com.best.ai_orchestrator.hitl;

import com.best.ai_orchestrator.core.RunState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂停中的运行状态存在哪。
 *
 * <p><b>抽成接口的目的，是让"存哪"变成一个部署决定，而不是一个代码决定。</b>
 *
 * <p>⚠️ <b>{@link InMemory} 不能上生产</b>：单机、重启即丢、多实例不共享。
 * 这三条里任何一条都会让"用户批准了但系统找不到那次运行"。
 * 正式部署用 {@link RedisRunStateStore}。
 */
public interface RunStateStore {

    void save(String runId, RunState state);

    Optional<RunState> load(String runId);

    void remove(String runId);

    /**
     * 进程内实现。
     *
     * <p>⚠️ 刻意用 {@code ConcurrentHashMap} 而不是普通 Map ——
     * 多个 HTTP 线程会同时读写，而且"暂停"和"恢复"通常落在不同请求上。
     */
    class InMemory implements RunStateStore {

        private final Map<String, RunState> map = new ConcurrentHashMap<>();

        @Override
        public void save(String runId, RunState state) {
            map.put(runId, state);
        }

        @Override
        public Optional<RunState> load(String runId) {
            return Optional.ofNullable(map.get(runId));
        }

        @Override
        public void remove(String runId) {
            map.remove(runId);
        }
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/hitl/RedisRunStateStore.java`

```java
package com.best.ai_orchestrator.hitl;

import com.best.ai_orchestrator.core.RunState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * 暂停状态的 Redis 实现 —— <b>正式部署用这个</b>。
 *
 * <p>它只需要做三件事：序列化、存取、给 TTL。没有别的东西。
 * <b>因为 {@link RunState} 本身刻意做成了"无参构造 + setter + 不引用框架类型"的 POJO</b>，
 * Jackson 能直接序列化/反序列化 —— 不需要为"持久化"改一行编排逻辑。
 *
 * <p>⚠️ <b>给 TTL 的理由</b>：暂停状态不是资产。一个用户点了批准就走的会话，
 * 在 Redis 里躺一周是纯浪费。默认 24 小时，配置项 {@code ai.orchestrator.run-ttl-hours}。
 *
 * <p>★ <b>序列化用自己的 ObjectMapper，不借用 Spring 注入的那个</b> ——
 * "跨请求恢复"是一条独立的序列化契约，它不能被全局配置（其他业务的
 * {@code spring.jackson.*}、Boot 升级后的默认值变化）悄悄改掉。
 * 曾踩过的坑：{@code RunEvent} 带 {@code Instant} 字段，裸 mapper 不注册
 * {@link JavaTimeModule} 会直接抛 {@code InvalidDefinitionException}；
 * 靠"Spring 那个恰好配了"是不够的，这里显式配好。
 */
@Slf4j
public class RedisRunStateStore implements RunStateStore {

    /** ★ 恢复链路的专用 mapper：自己注册模块、自己关特性，不依赖外部配置 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StringRedisTemplate redis;
    private final String namespace;
    private final Duration ttl;

    public RedisRunStateStore(StringRedisTemplate redis,
                              String namespace,
                              Duration ttl) {
        this.redis = redis;
        this.namespace = namespace;
        this.ttl = ttl;
    }

    private String key(String runId) {
        return "ai.orchestrator:run:" + namespace + ":" + runId;
    }

    @Override
    public void save(String runId, RunState state) {
        try {
            redis.opsForValue().set(key(runId), JSON.writeValueAsString(state), ttl);
        } catch (Exception e) {
            // ★ 这条必须抛出去：存失败 = 用户批准后找不到那次运行。
            //   静默吞掉的后果是"审批点完没反应"，而且完全看不出原因。
            log.error("暂停状态写入失败 runId={}", runId, e);
            throw new IllegalStateException("暂停状态写入失败，无法进入等待审批状态", e);
        }
    }

    @Override
    public Optional<RunState> load(String runId) {
        try {
            String json = redis.opsForValue().get(key(runId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(json, RunState.class));
        } catch (Exception e) {
            log.error("暂停状态读取失败 runId={}", runId, e);
            return Optional.empty();
        }
    }

    @Override
    public void remove(String runId) {
        redis.delete(key(runId));
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/memory/MemoryAccess.java`

```java
package com.best.ai_orchestrator.memory;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemory;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆的唯一出入口 —— <b>本模块与 ai-memory 之间的那一层薄适配</b>。
 *
 * <p><b>为什么要有这个类</b>：ai-memory 提供的是三个独立的 Bean
 * （{@code ChatMemoryProvider} / {@code MemoryRecallService} / {@code UserMemoryRecorder}），
 * 而编排器需要的是三句人话："刚才聊了什么" / "用户是谁" / "记住这次"。
 * 把三句话收在一个类里，编排器的构造函数就不会变成一长串记忆 Bean。
 *
 * <p>★ <b>它也把两个容易漏的责任收成了代码结构</b>：
 * <ol>
 *   <li><b>memoryId 不手拼</b> —— 统一走 {@link SessionKey#of}。
 *       手拼少一个 {@code ::} 的后果本项目踩过：解析在同步段抛异常，
 *       被 catch 成一条 warn，表现成"长期记忆永远写不进去但服务照跑"。</li>
 *   <li><b>失败一律降级不抛</b> —— 记忆是增强项，不是主流程。
 *       让一次召回失败把整轮编排打断，是错误的取舍。</li>
 * </ol>
 *
 * <p><b>会话历史和长期记忆是两件事，不要混：</b>
 * <table border="1">
 *   <tr><th></th><th>会话历史</th><th>长期记忆</th></tr>
 *   <tr><td>回答的问题</td><td>这次对话刚才聊了什么</td><td>用户是谁</td></tr>
 *   <tr><td>给谁看</td><td>Planner + Synthesizer</td><td>所有人（含 Worker）</td></tr>
 *   <tr><td>存储</td><td>Redis（TTL 滑动）</td><td>MySQL 事实表</td></tr>
 * </table>
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryAccess {

    private final ChatMemoryProvider chatMemoryProvider;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder recorder;

    /** 带进 Planner / Synthesizer 的对话历史最多取多少条 */
    private final int conversationTurns;

    /**
     * @return 渲染后的会话历史（不含本次请求）。读不到就返回空串 —— <b>不抛</b>
     */
    public String conversation(String memoryId) {
        try {
            ChatMemory memory = chatMemoryProvider.get(memoryId);
            if (memory == null) {
                return "";
            }
            List<ChatMessage> all = memory.messages();
            if (all == null || all.isEmpty()) {
                return "";
            }
            int from = Math.max(0, all.size() - Math.max(1, conversationTurns));
            return ChatMessageTexts.render(new ArrayList<>(all.subList(from, all.size())));
        } catch (Exception e) {
            log.warn("会话历史读取失败，按空处理。memoryId={}", memoryId, e);
            return "";
        }
    }

    /**
     * @return 渲染后的长期记忆文本。读不到就返回空串 —— <b>不抛</b>
     */
    public String longTerm(String userId, String query) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        try {
            List<UserMemory> facts = recallService.recall(userId, query);
            if (facts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("[关于这位用户的已知信息，按时间倒序，若冲突以靠前的为准]\n");
            for (UserMemory m : facts) {
                sb.append("- ").append(m.getContent()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("长期记忆召回失败，本次跳过。userId={}", userId, e);
            return "";
        }
    }

    /**
     * 触发一次<b>异步</b>的长期记忆抽取。
     *
     * <p><b>本方法不阻塞</b>：重的活（LLM 抽取 + 向量化 + 落库）在线程池里跑。
     *
     * <p>★ 传 {@link List#copyOf} 而不是原列表：调用方持有的可能是活引用
     * （比如某个 appender 通道的值），异步线程和主线程同时改它会抛
     * {@code ConcurrentModificationException} —— 而那个异常只打在异步线程的日志里。
     */
    public void remember(String memoryId, List<ChatMessage> turns) {
        try {
            recorder.recordAsync(memoryId, List.copyOf(turns));
        } catch (Exception e) {
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
    }

    /**
     * 把这一轮"用户问 + 系统答"追加进会话历史。
     *
     * <p>★ <b>由编排器在收敛时调用，而不是留给 Controller</b> ——
     * 理由和 {@link #remember} 相同："别忘了存"是人的纪律，
     * 收进代码结构才不会漏。漏了的症状是"第二轮对话没有上下文"，
     * 而那种问题在单轮测试里完全看不出来。
     *
     * <p><b>暂停（等人审批）时不调用</b> —— 那一轮还没有答案，不该写进历史。
     */
    public void appendExchange(String memoryId, String userMessage, String aiMessage) {
        try {
            ChatMemory memory = chatMemoryProvider.get(memoryId);
            if (memory == null) {
                return;
            }
            memory.add(UserMessage.from(userMessage));
            memory.add(AiMessage.from(aiMessage));
        } catch (Exception e) {
            log.warn("会话历史写入失败，memoryId={}", memoryId, e);
        }
    }

    /** memoryId 的唯一构造点。★ 不手拼，见类注释 */
    public static String memoryIdOf(String assistantId, String userId, String sessionId) {
        return SessionKey.of(assistantId, userId, sessionId).toMemoryId();
    }
}
```

---

## 批次 4 · plan + worker

> ⭐ **`WorkerExecutor` 是全模块唯一有 ReAct 循环的地方**，也是唯一会碰工具的地方。
> 三个 AiService 接口（`Planner` / `WorkerAgent` / `Synthesizer`）都**不挂 `.tools(...)`** ——
> 29 号 javap 实测：挂了就当场执行，审批来不及。模型只提议，执行在这里。
> ⚠️ `Planner` 是**两个方法**（`plan` / `refine`），第二个支撑循环迭代（第 5 节 ⑤）。


#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/plan/Planner.java`

```java
package com.best.ai_orchestrator.plan;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * 规划器 —— 把用户请求拆成若干<b>带显式 scope</b> 的子任务；以及在看到已有结论后，
 * 决定<b>要不要追加</b>新的子任务。
 *
 * <p><b>两个方法分别对应"任务分解"和"循环迭代"这两个构件。</b>
 * 只有第一个、没有第二个，这套东西就退化成一个并行版的 prompt chaining ——
 * 那正是 Anthropic 五式里最便宜的第一式，花 3.75 倍 token 只买到一个并行，不划算。
 *
 * <p>提示词里有三条来自生产经验的硬约束，每一条都对应一次真实的翻车：
 * <ol>
 *   <li><b>规模随复杂度缩放。</b> 不加这条，会出现"为简单查询起 50 个执行者"。
 *       原文的说法是要把扩展规则<b>写进提示词</b>，而不是靠模型自觉。</li>
 *   <li><b>子任务必须带边界。</b> 不加这条，会出现"几个子任务重复做同一件事，同时留下空白"。</li>
 *   <li><b>只有真要用前一个产出时才写 dependsOn。</b> 写多了会把本来能并行的子任务串起来，白等。</li>
 * </ol>
 *
 * <p>★ <b>{@code goal} 必须是消解过指代的完整描述</b> ——
 * "那它什么时候发货"要翻成"查订单 12345 的预计发货时间"。
 * <b>指代消解的责任在规划器，不在执行者</b>：执行者看不到对话历史（那是上下文隔离的一部分），
 * 所以它只能看到规划器翻译好的东西。
 */
public interface Planner {

    /**
     * 初次规划。
     *
     * @param conversation 渲染后的会话历史。<b>规划器是唯一需要它的地方之一</b> ——
     *                     没有它，第二轮问"那它什么时候发货"时，规划器不知道"它"是什么
     */
    @SystemMessage("""
            你是任务规划器。把用户的请求拆成若干子任务，每个子任务交给下面某个角色。

            ## 可用角色
            {{roles}}

            ## 本次对话到目前为止的内容（用于理解指代，如「它」「那个」）
            {{conversation}}

            ## 拆分原则
            1. **规模随复杂度缩放**：如果一句话就能回答，就只拆 1 个子任务。
               需要汇集多个来源的复杂请求，才拆 2~4 个。
               不要为了显得周全而多拆 —— 每多一个子任务都是真金白银。
            2. **每个子任务都必须有明确边界**：目标、期望产出、不要做什么。
               模糊的指令会让执行者跑偏，或者几个子任务做重复的事。
            3. **只有确实要用前一个子任务的产出时，才写 dependsOn。**
               互相独立的子任务会被并行执行，写错依赖只会让它白等。
            4. **goal 必须是自包含的完整描述**：把「它」「那个」这类指代
               依据上面的对话历史替换成具体对象。执行者看不到对话历史。
            5. **不要把需要你自己判断的事拆出去**，只拆「需要查数据 / 查资料」的部分。

            ## 输出
            只输出结构化结果，不要解释、不要附加文字。
            """)
    PlanOutput plan(@V("roles") String roles,
                    @V("conversation") String conversation,
                    @UserMessage String request);

    /**
     * 循环迭代 —— 看完已有结论，决定要不要<b>追加</b>子任务。
     *
     * <p>★ <b>这是本模块与"并行版 prompt chaining"的分界线。</b>
     * Anthropic 的 LeadResearcher 明确有这一步：
     * "it can create additional subagents or refine its strategy"。
     *
     * <p><b>提示词要求它看 {@code uncovered} 和低置信度的结论</b> ——
     * 那两类正是"哪里还需要补一轮"的信号。没有这些字段，它只能瞎猜。
     *
     * @param outcomes 已有子任务的浓缩结论（含置信度和未覆盖项）
     * @return 要追加的子任务。<b>返回空数组表示"不需要再补"</b>
     */
    @SystemMessage("""
            你是任务规划器。下面是这次任务已经完成的子任务及其结论。

            ## 可用角色
            {{roles}}

            ## 本次对话到目前为止的内容
            {{conversation}}

            ## 已完成的子任务及其结论
            {{outcomes}}

            ## 现在要判断
            基于已有结论，回答用户最初的问题是否还缺什么。

            **追加新子任务的判据（满足任一条才追加）：**
            - 某个子任务的「未覆盖」项正好是用户问题需要的
            - 某两个子任务的结论互相矛盾，需要第三个去交叉验证
            - 某个结论的置信度很低（低于 0.6），而它又是关键依据

            **不要追加的情况（更重要）：**
            - 信息已经够了，只是不够漂亮 → 不要追加，让汇总环节去组织语言
            - 只是想再确认一次 → 不要追加，重复劳动
            - 追加的子任务和已完成的某个本质相同 → 不要追加

            如果没有该追加的，就返回空数组。**空数组是完全正常的答案。**

            ## 输出
            只输出结构化结果，不要解释。
            """)
    PlanOutput refine(@V("roles") String roles,
                      @V("conversation") String conversation,
                      @V("outcomes") String outcomes,
                      @UserMessage String request);

    record PlanOutput(List<PlannedTask> tasks) {
    }

    record PlannedTask(
            @Description("短 id，如 t1 / t2。被别的子任务 dependsOn 时引用它")
            String id,

            @Description("这个子任务要做什么。必须自包含：把「它」「那个」这类指代替换成具体对象")
            String goal,

            @Description("什么样算做完。例如：给出三项结论并附来源")
            String expectedOutput,

            @Description("不要做什么。例如：不要修改数据；只给事实，不给建议")
            String boundaries,

            @Description("交给哪个角色。必须是可用角色列表里的名字，原样照抄")
            String roleName,

            @Description("依赖哪些子任务的 id。只有确实要用它的产出时才写；没有依赖就留空数组")
            List<String> dependsOn
    ) {
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/worker/WorkerAgent.java`

```java
package com.best.ai_orchestrator.worker;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;
import java.util.Map;

/**
 * 执行者 —— <b>只做一轮</b>：给出结论，或者"我要调某个工具"。
 *
 * <p><b>为什么只做一轮。</b> 如果多轮循环写在这里面，编排器就看不见循环边界，
 * 也就无法在两次工具调用之间插人工审批 —— 这是本项目踩过的坑
 * （放在节点内部的循环，审批最多只能拦住第一次）。
 * 循环放在编排器里（就是一段普通 Java 的 while），
 * 于是每一步都是可中断、可记录、可恢复的。<b>而且不需要图引擎。</b>
 *
 * <p><b>它的提示词里没有"对话历史"，只有</b>：
 * 本次任务的 scope + <b>它自己</b>上一轮做过什么（{@code {{history}}}）。
 * <b>这就是上下文隔离。</b>
 *
 * <p>那"看不见"会不会导致 Cognition 说的那种"画风不一致"？
 * <b>会 —— 如果两个子任务实际上互相依赖的话。</b>
 * 所以判据不是"要不要隔离"，而是<b>"这两个子任务需不需要看见彼此才能做对"</b>：
 * 不需要 → 并行 + 隔离是对的；需要 → 它们本来就不该被拆成两个（写 dependsOn 串起来）。
 *
 * <p>★ <b>工具不挂给 AiServices</b>（{@code .tools(...)} 一行都没有）——
 * 挂了之后 LangChain4j 会在本方法内部当场执行工具，而审批是跨 HTTP 请求的异步动作，
 * "工具已经跑了才问要不要批准"没有意义。所以模型只<b>提议</b>，执行由
 * {@code WorkerExecutor} 做。
 */
public interface WorkerAgent {

    @SystemMessage("""
            你是{{role}}。

            ## 你的工作准则
            {{rules}}

            ## 本次任务
            {{task}}

            ## 你可以调用的工具
            {{tools}}

            ## 关于这位用户已知的信息（可能为空）
            {{memory}}

            ## 本轮之前你自己已经做过什么（可能为空）
            {{history}}

            ## 输出要求
            1. 信息够了 → 把结论写进 summary，finish 设为 true。
            2. 还需要查数据 → 把工具名写进 tool、参数写进 toolArgs，finish 设为 false。
            3. **一次只提议一个工具。** 看到它的返回结果后再决定下一步。
            4. 只能从上面的工具清单里选，不要编造工具名。
            5. **不要假装已经执行了** —— 真正的执行由编排器完成。
            6. summary 写**浓缩后的结论**，不要贴工具返回的原文。
               有没查到或不确定的地方，写进 uncovered。
            """)
    WorkerOutput turn(@V("role") String role,
                      @V("rules") String rules,
                      @V("task") String task,
                      @V("tools") String tools,
                      @V("memory") String memory,
                      @V("history") String history);

    /**
     * 一轮的产出。
     *
     * <p>字段刻意做成"两种互斥形态"：<b>要么给结论（finish=true + summary），
     * 要么提议工具（finish=false + tool）</b>。
     * 而不是"一段自由文本 + 一个可选的工具字段" ——
     * 后者没法在编排器里做可靠的分支判断。
     */
    record WorkerOutput(
            @Description("本轮的一句话推理，便于排查。可以很短，也可以留空")
            String thought,

            @Description("信息是否已经足够。够了填 true，还需要调工具填 false")
            boolean finish,

            @Description("结论的浓缩摘要。不要贴工具返回原文。finish 为 false 时可以留空")
            String summary,

            @Description("结论依据的关键位置（文档名 / 表名 / 接口名）。没有就留空数组")
            List<String> evidence,

            @Description("对这个结论的把握，0 到 1 之间的小数")
            double confidence,

            @Description("没有查到或者不确定的部分。没有就留空数组")
            List<String> uncovered,

            @Description("要调用的工具名。不需要调用工具时留空字符串")
            String tool,

            @Description("工具参数，JSON 对象。不需要调用工具时留空对象")
            Map<String, Object> toolArgs
    ) {
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/worker/WorkerExecutor.java`

```java
package com.best.ai_orchestrator.worker;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.core.PendingApproval;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.core.TaskOutcome;
import com.best.ai_orchestrator.core.WorkerTurn;
import com.best.ai_orchestrator.hitl.ApprovalGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行器 —— <b>一个子任务的工具循环就发生在这里</b>。
 *
 * <p>它只做三件事，但三件都必须做对：
 * <ol>
 *   <li>给执行者<b>它自己的</b>上下文（{@code node.getTurns()}），不掺别的</li>
 *   <li>工具<b>要不要先问人</b>由 {@link ApprovalGate} 决定；
 *       需要时<b>立刻返回 {@link AwaitingApproval}、不执行</b> ——
 *       停下那一刻工具真的没被执行，这是审批唯一有意义的形态</li>
 *   <li>循环有上限（{@code spec.maxToolRounds()}）—— 上限是刹车，不是目的地</li>
 * </ol>
 *
 * <p><b>为什么这里能"在循环中间停下"。</b> 因为循环是普通 Java 的 while，
 * 而暂停就是 {@code return}。如果循环住在图里，中断就只能是<b>节点级</b>的 ——
 * 那是本项目 25 号踩过的坑：审批最多拦住第一次。
 */
@Slf4j
public class WorkerExecutor {

    /** 没有提议时执行者写的标记 */
    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 日志里工具结果最多留多少字 */
    private static final int RESULT_LOG_LIMIT = 200;

    private final Map<String, AgentRole> roles;
    private final WorkerAgent worker;
    private final McpClientRegister mcpRegister;
    private final McpToolCatalog toolCatalog;
    private final ApprovalGate gate;

    public WorkerExecutor(Map<String, AgentRole> roles,
                          WorkerAgent worker,
                          McpClientRegister mcpRegister,
                          McpToolCatalog toolCatalog,
                          ApprovalGate gate) {
        this.roles = roles;
        this.worker = worker;
        this.mcpRegister = mcpRegister;
        this.toolCatalog = toolCatalog;
        this.gate = gate;
    }

    /** 一个子任务的执行结果：要么做完，要么卡在审批上 */
    public sealed interface Execution {
        record Finished(TaskOutcome outcome) implements Execution {
        }

        record AwaitingApproval(PendingApproval pending) implements Execution {
        }
    }

    public Execution execute(TaskNode node, RunState state) {
        var spec = node.getSpec();
        AgentRole role = roles.get(spec.roleName());
        if (role == null) {
            // ★ 这是配置错误，不是运行失败 —— 早失败好过跑一半才发现没人接活
            return new Execution.Finished(TaskOutcome.failed(spec.id(),
                    "没有名为 " + spec.roleName() + " 的角色（检查 ai.orchestrator.roles 配置）", 0));
        }

        List<McpTool> tools = (role.mcpClientKey() == null)
                ? List.of()                                   // 纯对话角色：没有工具可调
                : toolCatalog.list(role.mcpClientKey());
        String toolList = renderTools(tools);
        int tokens = 0;
        int maxRounds = spec.maxToolRounds();

        for (int round = 0; round < maxRounds; round++) {
            // ---- A. 结算上一轮留下的审批（如果有） ----
            PendingApproval pending = node.getPending();
            if (pending != null) {
                if (node.getApproved() == null) {
                    // 人还没答 → 原地继续等。★ 这一步让"恢复"天然幂等：
                    // 重复调 resume 不会重复执行工具
                    return new Execution.AwaitingApproval(pending);
                }
                boolean ok = node.getApproved();
                node.clearDecision();
                if (ok) {
                    node.addTurn(WorkerTurn.toolResult(callTool(pending.tool(), pending.args(), role)));
                } else {
                    node.addTurn(WorkerTurn.toolResult("[人工拒绝] 未执行 " + pending.tool()
                            + "。请改用其他方式，或说明你做不到。"));
                }
            }

            // ---- B. 问执行者一轮 ----
            String history = node.renderHistory();
            WorkerAgent.WorkerOutput out;
            try {
                out = worker.turn(role.name(), role.systemPrompt(), spec.render(), toolList,
                        state.getContext().memoryText(), history);
            } catch (Exception e) {
                log.error("子任务 {} 的模型调用失败", spec.id(), e);
                return new Execution.Finished(TaskOutcome.failed(spec.id(),
                        "模型调用失败: " + e.getMessage(), tokens));
            }
            tokens += estimate(history.length() + toolList.length() + spec.render().length(), out);

            if (out.thought() != null && !out.thought().isBlank()) {
                node.addTurn(WorkerTurn.thought(out.thought()));
            }

            // ---- C. 它说做完了 ----
            if (out.finish() || out.tool() == null || out.tool().isBlank()) {
                String summary = out.summary() == null ? "" : out.summary();
                if (summary.isBlank()) {
                    // ★ finish=true 却没给摘要 → 当失败处理，不要静默产出一个空 outcome
                    return new Execution.Finished(TaskOutcome.failed(spec.id(),
                            "执行者标记完成但没有给出结论摘要", tokens));
                }
                return new Execution.Finished(TaskOutcome.done(spec.id(), summary,
                        out.evidence(), out.confidence(), out.uncovered(), tokens));
            }

            // ---- D. 它提议了工具 ----
            String args = toJson(out.toolArgs());
            String toolName = normalizeTool(out.tool(), tools);
            if (toolName == null) {
                // ★ 模型会编工具名。不在清单里就当场写回，让它重选 ——
                //   不要等执行阶段才暴露，那时已经烧掉一次模型调用和一次网络往返
                node.addTurn(WorkerTurn.toolCall(out.tool(), args));
                node.addTurn(WorkerTurn.toolResult("[工具不存在] " + out.tool()
                        + " 不在你的工具清单里，请重新选择。"));
                continue;
            }
            node.addTurn(WorkerTurn.toolCall(toolName, args));

            ToolRisk risk = toolCatalog.riskOf(toolName);
            if (gate.requiresApproval(risk)) {
                PendingApproval p = new PendingApproval(spec.id(), spec.goal(), toolName, args, risk,
                        "工具 " + toolName + " 危险等级为 " + risk + "，需要人工批准");
                node.awaitApproval(p);
                return new Execution.AwaitingApproval(p);   // ★ 工具没有执行
            }

            node.addTurn(WorkerTurn.toolResult(callTool(toolName, args, role)));
        }

        // ---- 循环用尽 ----
        return new Execution.Finished(TaskOutcome.failed(spec.id(),
                "达到工具轮次上限 " + maxRounds + " 轮，未能在限定预算内完成", tokens));
    }

    /**
     * 真正执行 MCP 工具。
     *
     * <p><b>按 {@code role.mcpClientKey()} 选客户端</b> —— 这保证
     * "提示词里看到的工具清单"和"真正能调的工具"是同一个来源，
     * 越权在网关那一层就被拦掉了。
     */
    private String callTool(String tool, String args, AgentRole role) {
        try {
            McpClient client = mcpRegister.get(role.mcpClientKey());
            if (client == null) {
                log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", role.mcpClientKey(), tool);
                return "[工具未能执行] 未配置 " + role.mcpClientKey() + " 的 MCP 通道";
            }
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(args == null || args.isBlank() ? "{}" : args)
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, role.mcpClientKey(), abbreviate(text));
            return "[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text;
        } catch (Exception e) {
            log.error("工具执行异常: tool={} by={} args={}", tool, role.mcpClientKey(), args, e);
            return "[工具执行失败] " + e.getMessage();
        }
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

    /** 校验模型给的工具名。<b>返回 null 表示不在清单里</b> */
    private String normalizeTool(String proposed, List<McpTool> available) {
        if (proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())) {
            return null;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if (!exists) {
            log.warn("执行者提议了一个不在清单里的工具: [{}]。可用: {}",
                    trimmed, available.stream().map(McpTool::name).toList());
            return null;
        }
        return trimmed;
    }

    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("工具参数序列化失败，按空对象处理: {}", args, e);
            return "{}";
        }
    }

    /**
     * token 估算。
     *
     * <p>★ <b>这是估算，不是精确值。</b> MCP 的 {@code executeTool} 不返回用量，
     * 而 AiServices 也不会把每次调用的 token 交出来。
     * 精确值在 {@code ai_token_usage} 台账里（SDK 层监听器写的）——
     * <b>两者不能混在一张表里</b>，估算只用于"及时刹车"。
     */
    private int estimate(int promptChars, WorkerAgent.WorkerOutput out) {
        int chars = promptChars;
        chars += len(out.thought()) + len(out.summary()) + len(out.tool()) + toJson(out.toolArgs()).length();
        // ★ 用 for 而不是 forEach：lambda 里不能修改捕获的局部变量
        if (out.evidence() != null) {
            for (String s : out.evidence()) {
                chars += len(s);
            }
        }
        if (out.uncovered() != null) {
            for (String s : out.uncovered()) {
                chars += len(s);
            }
        }
        // 粗略：中英混合按 2 字符 ≈ 1 token。宁可高估一点 —— 低估会让预算失灵
        return Math.max(1, chars / 2);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        // ★ 必须 replaceAll：replace 是按字面量替换，"\\s+" 这五个字符根本不存在于文本里
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= RESULT_LOG_LIMIT
                ? oneLine
                : oneLine.substring(0, RESULT_LOG_LIMIT) + "...(截断)";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
```

---

## 批次 5 · orchestrator + config + controller

> **`Orchestrator.drive` 是整个模块唯一的控制流**（约 270 行，含注释）。
> `run` / `resume` 都走它 —— "恢复"不是另一套逻辑，是同一个 while 再转一次。
> ⭐ `store.save` / `store.remove` 都收在编排器内部，调用方拿不到"忘了存"的机会。
> ⚠️ `AiOrchestratorAutoConfiguration` 走 `AutoConfiguration.imports`（39 号结论：
> 库模块不被业务模块组件扫描，`@Component` 拿不到）。


#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/orchestrator/TerminationPolicy.java`

```java
package com.best.ai_orchestrator.orchestrator;

import com.best.ai_orchestrator.core.Plan;
import com.best.ai_orchestrator.core.RunState;

/**
 * 终止条件 —— <b>组合式，而且必须有一条兜底</b>。
 *
 * <p>只有"模型说完成了"是不够的：它会转不停。所以这里并列检查四件事，
 * 任何一条成立就收敛，并<b>记录命中的是哪一条</b>（这条原因会进事件流）。
 *
 * <p>四条按"从软到硬"排列：
 * <ol>
 *   <li><b>全部子任务完成</b> —— 正常收敛</li>
 *   <li><b>连续 N 轮没有新进展</b> —— 增量式判据。它防的是"每轮都有活干，
 *       但干的都是重复的"：循环迭代追加的子任务如果总是"再确认一次"，
 *       已完成数就不涨，这时该停</li>
 *   <li><b>预算耗尽</b> —— 成本兜底</li>
 *   <li><b>达到最大轮次</b> —— 最后的防呆墙。它保证任何逻辑漏洞
 *       都不会让系统真的跑飞</li>
 * </ol>
 */
public class TerminationPolicy {

    public record Decision(boolean stop, String reason) {
        static Decision go() {
            return new Decision(false, "");
        }

        static Decision stop(String reason) {
            return new Decision(true, reason);
        }
    }

    private final int maxRounds;
    private final int noProgressLimit;

    public TerminationPolicy(int maxRounds, int noProgressLimit) {
        this.maxRounds = Math.max(1, maxRounds);
        this.noProgressLimit = Math.max(1, noProgressLimit);
    }

    public Decision evaluate(Plan plan, RunState state) {
        if (plan.allDone()) {
            return Decision.stop("全部子任务完成");
        }
        if (state.getNoProgressRounds() >= noProgressLimit) {
            return Decision.stop("连续 " + noProgressLimit + " 轮没有新的子任务完成");
        }
        if (state.getBudget().exhausted()) {
            return Decision.stop("token 预算耗尽");
        }
        if (state.getRounds() >= maxRounds) {
            return Decision.stop("达到最大轮次 " + maxRounds);
        }
        return Decision.go();
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/orchestrator/Synthesizer.java`

```java
package com.best.ai_orchestrator.orchestrator;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 汇总器 —— 把若干子任务的结论合成一个给用户的回答。
 *
 * <p><b>它是"错误不静默合并"的最后一道关。</b> 提示词里明确要求：
 * 标注为「未完成」的子任务必须原样告诉用户。
 *
 * <p>没有这一条会发生什么：失败的子任务被跳过，答案看起来完整，
 * 但缺了一块 —— 而且用户完全看不出。这比"明确说这块没查到"糟糕得多。
 *
 * <p>它也看 {@code {{conversation}}}（最近几轮）—— 否则它会给出一个
 * "答非所问"的答案：用户问"那它什么时候发货"，它答"订单 12345 的状态是已签收"。
 */
public interface Synthesizer {

    @SystemMessage("""
            你要把下面几个子任务的结论，合成一个给用户的回答。

            ## 用户最初的问题
            {{request}}

            ## 本次对话最近的内容（用来对齐语气和指代）
            {{conversation}}

            ## 子任务结论
            {{outcomes}}

            ## 要求
            1. 直接回答用户的问题，不要罗列「子任务 1 说了什么」这种过程。
            2. **标注为「未完成」的部分必须原样告诉用户** —— 不要说「我查了」然后跳过它。
               没查到就直说没查到，并说明缺的是什么。
            3. 如果不同子任务的结论互相矛盾，把矛盾指出来，不要替用户选一个。
            4. 不要补充上面材料里没有的信息。
            5. 用中文，简洁。
            """)
    String synthesize(@V("request") String request,
                      @V("conversation") String conversation,
                      @V("outcomes") String outcomes);
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/orchestrator/TaskDispatcher.java`

```java
package com.best.ai_orchestrator.orchestrator;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_orchestrator.core.PendingApproval;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 派发器 —— 决定"这一批是并行还是顺序"，并把结果收回来。
 *
 * <p><b>只有依赖已满足的子任务才会同时出现在一批里</b> —— 这是并行的前置条件，
 * 也是对"两个子任务实际上互相依赖却被并行出去"（生产环境最常见的翻车方式）的正面回应。
 *
 * <p>★ <b>线程池刻意是有界的 + CallerRunsPolicy</b>：
 * 无界队列会让积压变成<b>静默延迟</b>（任务越排越久，但没有任何报错），
 * 有界队列 + 让提交线程自己干，才能把积压变成<b>可见的降速</b>。
 * 这是本项目 33 号记录过的教训。
 */
@Slf4j
public class TaskDispatcher {

    private final WorkerExecutor executor;
    private final ExecutorService pool;
    private final int maxParallel;

    public TaskDispatcher(WorkerExecutor executor, int maxParallel) {
        this.executor = executor;
        this.maxParallel = Math.max(1, maxParallel);
        this.pool = new ThreadPoolExecutor(
                this.maxParallel,
                this.maxParallel,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.maxParallel),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public sealed interface DispatchResult {
        record AllDone() implements DispatchResult {
        }

        record Paused(PendingApproval pending) implements DispatchResult {
        }
    }

    public DispatchResult dispatch(List<TaskNode> batch, RunState state) {
        if (batch.isEmpty()) {
            return new DispatchResult.AllDone();
        }
        if (batch.size() == 1 || maxParallel == 1) {
            // 顺序：一个做完再下一个。好处是第一个需要审批时立刻停，后面的不白跑
            for (TaskNode node : batch) {
                WorkerExecutor.Execution e = executor.execute(node, state);
                if (e instanceof WorkerExecutor.Execution.AwaitingApproval a) {
                    return new DispatchResult.Paused(a.pending());
                }
                node.finish(((WorkerExecutor.Execution.Finished) e).outcome());
            }
            return new DispatchResult.AllDone();
        }

        // 并行：先全部提交，再逐个收结果
        List<CompletableFuture<WorkerExecutor.Execution>> futures = new ArrayList<>();
        for (TaskNode node : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> runOne(node, state), pool));
        }

        // ★ 先把所有 Finished 落库，再返回第一个 AwaitingApproval。
        //   顺序反过来会丢掉同批里其他子任务已经做完的结果。
        PendingApproval firstPending = null;
        for (int i = 0; i < batch.size(); i++) {
            WorkerExecutor.Execution e = get(futures.get(i), batch.get(i));
            if (e instanceof WorkerExecutor.Execution.AwaitingApproval a) {
                if (firstPending == null) {
                    firstPending = a.pending();
                }
                continue;
            }
            batch.get(i).finish(((WorkerExecutor.Execution.Finished) e).outcome());
        }
        return firstPending == null ? new DispatchResult.AllDone() : new DispatchResult.Paused(firstPending);
    }

    /**
     * 在池线程里跑一个子任务。
     *
     * <p>★ <b>必须自己开 {@link AiUserContext}</b>：ThreadLocal 不会跨线程继承。
     * 漏了这行的症状是"并行时 token 台账里的 userId 是 anonymous" ——
     * 而顺序执行时完全正常，所以只在开启并行后才暴露。
     */
    private WorkerExecutor.Execution runOne(TaskNode node, RunState state) {
        try (var ignored = AiUserContext.open(state.getContext().userId())) {
            return executor.execute(node, state);
        } catch (Exception e) {
            log.error("子任务 {} 执行异常", node.getSpec().id(), e);
            return new WorkerExecutor.Execution.Finished(
                    com.best.ai_orchestrator.core.TaskOutcome.failed(node.getSpec().id(),
                            "执行异常: " + e.getMessage(), 0));
        }
    }

    private WorkerExecutor.Execution get(CompletableFuture<WorkerExecutor.Execution> future, TaskNode node) {
        try {
            return future.get();
        } catch (Exception e) {
            log.error("子任务 {} 等待结果失败", node.getSpec().id(), e);
            return new WorkerExecutor.Execution.Finished(
                    com.best.ai_orchestrator.core.TaskOutcome.failed(node.getSpec().id(),
                            "等待结果失败: " + e.getMessage(), 0));
        }
    }

    /** 优雅停机。池是应用级的，不需要频繁创建销毁 */
    public void shutdown() {
        pool.shutdown();
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/orchestrator/Orchestrator.java`

```java
package com.best.ai_orchestrator.orchestrator;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_orchestrator.config.OrchestratorProperties;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.core.Budget;
import com.best.ai_orchestrator.core.Plan;
import com.best.ai_orchestrator.core.RunOutcome;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.core.TaskOutcome;
import com.best.ai_orchestrator.core.TaskSpec;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.observability.RunEvent;
import com.best.ai_orchestrator.plan.Planner;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排器 —— <b>整个模块唯一的控制流</b>。
 *
 * <p><b>它是一段普通 Java 的主循环，不是一张图。</b> 这是本模块最核心的设计决定，
 * 理由是：
 * <ul>
 *   <li>你要的形状是"所有请求相同、只是内容不同" —— 那是<b>循环</b>，不是图。
 *       图引擎真正提供的是"任意拓扑 + 编译期图校验 + 可视化"。</li>
 *   <li>"可中断 / 可暂停 / 可恢复"<b>不需要图引擎</b>：一个可序列化的
 *       {@link RunState} + sealed {@link RunOutcome} 就够。
 *       {@link #run} 和 {@link #resume} 走的是<b>同一个</b> {@link #drive}。</li>
 *   <li>中断 = {@code return}。<b>代码放哪就能停在哪</b> —— 循环内部也行。
 *       如果循环住在图里，中断就只能是节点级的（审批最多拦住第一次，
 *       这是本项目 25 号踩过的坑）。</li>
 * </ul>
 *
 * <p><b>主循环每一步：</b>
 * <pre>
 *   while (true) {
 *       ① 终止判定（全部完成 / 无新进展 / 预算耗尽 / 轮次上限）—— 记录命中哪条
 *       ② 取下一批（依赖已满足的才同批）
 *       ③ 派发执行（顺序或并行；每个子任务有自己的上下文）
 *       ④ 记账 + 事件
 *       ⑤ 若有人要审批 → 存 state → return Paused        ★ 工具没有执行
 *       ⑥ 循环迭代：问规划器"基于已有结论，还要补什么" → plan.add(...)
 *   }
 * </pre>
 *
 * <p>★ <b>{@link RunStateStore} 的存取收在编排器内部</b>（{@code ⑤}），
 * 调用方拿不到"忘了存"的机会 —— 把容易漏的责任从"人的纪律"变成"代码结构"。
 * 这和 33 号把 {@code SessionKey} 收进构造器是同一个手法。
 */
@Slf4j
@Component
public class Orchestrator {

    private final Planner planner;
    private final Synthesizer synthesizer;
    private final TaskDispatcher dispatcher;
    private final TerminationPolicy policy;
    private final RunStateStore store;
    private final MemoryAccess memory;
    private final OrchestratorProperties props;
    private final Map<String, AgentRole> roles;

    public Orchestrator(Planner planner,
                        Synthesizer synthesizer,
                        TaskDispatcher dispatcher,
                        TerminationPolicy policy,
                        RunStateStore store,
                        MemoryAccess memory,
                        OrchestratorProperties props,
                        Map<String, AgentRole> roles) {
        // ★ 角色表为空 = 没人告诉编排器"有哪些专家可用" → 现在就失败。
        // 放到运行期才暴露的话，症状是"规划器随便派个人、然后全都失败"，
        // 排查时要翻到很后面才发现根因是没角色
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何角色可用。角色是业务知识，请在业务模块里定义 RoleRules 实现，"
                            + "并通过 @Bean 提供 Map<String, AgentRole>（key = 角色名）");
        }
        this.planner = planner;
        this.synthesizer = synthesizer;
        this.dispatcher = dispatcher;
        this.policy = policy;
        this.store = store;
        this.memory = memory;
        this.props = props;
        this.roles = roles;
    }

    /**
     * 开始一次运行。
     *
     * @param request 本次用户消息（<b>原始</b>，未消解指代）
     */
    public RunOutcome run(String request, RunState.RunContext ctx) {
        String runId = UUID.randomUUID().toString();
        Budget budget = new Budget(props.getTokenBudget(), props.getPerTaskTokenBudget());
        RunState state = new RunState(runId, request, ctx, budget);

        // ---- 规划 ----
        List<TaskSpec> specs;
        try {
            specs = toSpecs(planner.plan(rolesText(), ctx.conversationText(), request).tasks());
        } catch (Exception e) {
            log.error("规划失败 runId={}", runId, e);
            return new RunOutcome.Failed(runId, "规划失败: " + e.getMessage(), state.report());
        }
        state.setPlan(new Plan(specs.stream().map(TaskNode::new).toList()));
        if (state.getPlan().size() == 0) {
            return new RunOutcome.Failed(runId, "规划器没有产出任何子任务", state.report());
        }
        log.info("运行开始 runId={} 子任务数={}", runId, state.getPlan().size());
        return drive(state);
    }

    /**
     * 恢复一次被审批打断的运行。
     *
     * <p><b>只回答 {@code taskId} 指定的那一个待审批</b>，不是全部。
     * 同批里如果有两个子任务都在等审批，用户需要分别批两次 ——
     * 一把全批会把"第二个"也放过去，那是错误的。
     */
    public RunOutcome resume(RunState state, String taskId, boolean approved) {
        TaskNode node = state.getPlan().getNodes().stream()
                .filter(n -> n.getSpec().id().equals(taskId))
                .findFirst()
                .orElse(null);

        if (node == null || node.getPending() == null) {
            log.warn("resume 指定的 taskId={} 不是待审批状态，忽略", taskId);
        } else if (node.getApproved() != null) {
            log.warn("taskId={} 已经被回答过，忽略重复审批", taskId);
        } else {
            node.decide(approved);
        }
        return drive(state);
    }

    /** ★ 主循环。run 和 resume 都走这里 —— "恢复"就是同一个 while 再转一次 */
    private RunOutcome drive(RunState state) {
        Plan plan = state.getPlan();
        String memoryId = MemoryAccess.memoryIdOf(
                state.getContext().assistantId(), state.getContext().userId(), state.getContext().sessionId());

        try (var ignored = AiUserContext.open(state.getContext().userId())) {
            while (true) {
                // ---- ① 终止判定 ----
                TerminationPolicy.Decision decision = policy.evaluate(plan, state);
                if (decision.stop()) {
                    converge(state, plan, decision.reason());
                    break;
                }

                // ---- ② 取下一批（预算紧张时降并行度） ----
                state.beginRound();
                int parallel = state.getBudget().shouldDowngrade() ? 1 : props.getMaxParallel();
                List<TaskNode> batch = plan.nextBatch(parallel);
                if (batch.isEmpty()) {
                    // 依赖成环，或者没有可执行的。不要死循环
                    converge(state, plan, "没有可执行的子任务（依赖可能成环）");
                    break;
                }
                batch.forEach(n -> state.addEvent(RunEvent.dispatched(n.getSpec().id(), n.getSpec().goal())));

                // ---- ③ 执行 ----
                TaskDispatcher.DispatchResult result = dispatcher.dispatch(batch, state);

                // ---- ④ 记账 + 事件 ----
                for (TaskNode n : batch) {
                    if (n.isDone()) {
                        TaskOutcome o = n.getOutcome();
                        state.getBudget().consume(o.tokensUsed());
                        state.addEvent(RunEvent.completed(o.taskId(),
                                o.isFailed() ? o.error() : o.summary(), o.confidence(), o.tokensUsed()));
                    }
                }

                // ---- ⑤ 有人要审批 → 存 state，return ----
                if (result instanceof TaskDispatcher.DispatchResult.Paused paused) {
                    state.setTerminationReason(null);
                    store.save(state.getRunId(), state);
                    log.info("运行暂停 runId={} 等待审批 task={} tool={}",
                            state.getRunId(), paused.pending().taskId(), paused.pending().tool());
                    return new RunOutcome.Paused(state.getRunId(), paused.pending(), state, state.report());
                }

                // ---- ⑥ 循环迭代 ----
                state.endRound();
                if (!plan.allDone() && !state.getBudget().exhausted()) {
                    refine(state, plan);
                }
            }
        }

        return finish(state, memoryId);
    }

    /**
     * 循环迭代：问规划器"基于已有结论，还要补什么子任务"。
     *
     * <p>★ <b>这是本模块与"并行版 prompt chaining"的分界线。</b>
     * 失败时<b>按"不再追加"处理</b>，不让一次 refine 失败毁掉整轮 ——
     * 已经做完的子任务结果仍然有效，只是少了补洞的机会。
     */
    private void refine(RunState state, Plan plan) {
        try {
            List<TaskSpec> extra = toSpecs(planner.refine(
                    rolesText(),
                    state.getContext().conversationText(),
                    plan.renderOutcomes(),
                    state.getRequest()).tasks());
            plan.add(extra);
        } catch (Exception e) {
            log.warn("循环迭代失败，按不再追加处理。runId={}", state.getRunId(), e);
        }
    }

    private void converge(RunState state, Plan plan, String reason) {
        state.setTerminationReason(reason);
        state.addEvent(RunEvent.converged(reason, plan.doneCount(), plan.size()));
        log.info("运行收敛 runId={} 原因={} 已完成={}/{}",
                state.getRunId(), reason, plan.doneCount(), plan.size());
    }

    /** 汇总 + 触发长期记忆写入 */
    private RunOutcome finish(RunState state, String memoryId) {
        Plan plan = state.getPlan();
        String answer;
        try {
            answer = synthesizer.synthesize(state.getRequest(),
                    state.getContext().conversationText(), plan.renderOutcomes());
        } catch (Exception e) {
            log.error("汇总失败，退化为按子任务罗列。runId={}", state.getRunId(), e);
            // ★ 汇总失败也要给用户一个答案 —— 退化为罗列，不要整个失败
            answer = "（汇总环节出错，以下是各子任务的原始结论）\n" + plan.renderOutcomes();
        }

        // 会话历史 + 长期记忆都由编排器写，不留给调用方 —— "别忘了存"是人的纪律，
        // 收进代码结构才不会漏。漏了的症状（第二轮没有上下文）在单轮测试里完全看不出来
        memory.appendExchange(memoryId, state.getRequest(), answer);
        memory.remember(memoryId, List.of(UserMessage.from(state.getRequest()), AiMessage.from(answer)));
        store.remove(state.getRunId());

        return new RunOutcome.Completed(state.getRunId(), answer, state.report());
    }

    // ---- 小工具 ----

    private List<TaskSpec> toSpecs(List<Planner.PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .map(t -> new TaskSpec(t.id(), t.goal(), t.expectedOutput(), t.boundaries(),
                        t.roleName(), t.dependsOn(), props.getMaxToolRounds()))
                .toList();
    }

    /** 渲染"可用角色"清单，给规划器看 */
    private String rolesText() {
        Map<String, AgentRole> sorted = new LinkedHashMap<>(roles);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, role) ->
                sb.append("- ").append(key).append("：").append(role.description()).append('\n'));
        return sb.toString();
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/config/AiOrchestratorAutoConfiguration.java`

```java
package com.best.ai_orchestrator.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.hitl.ApprovalGate;
import com.best.ai_orchestrator.hitl.RedisRunStateStore;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.orchestrator.Orchestrator;
import com.best.ai_orchestrator.orchestrator.Synthesizer;
import com.best.ai_orchestrator.orchestrator.TaskDispatcher;
import com.best.ai_orchestrator.orchestrator.TerminationPolicy;
import com.best.ai_orchestrator.plan.Planner;
import com.best.ai_orchestrator.worker.WorkerAgent;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ai-orchestrator} 的装配。
 *
 * <p><b>为什么走自动装配而不是 {@code @Component} + 组件扫描</b>：
 * 业务模块的 {@code @SpringBootApplication} 只扫描自己的包，
 * 扫不到 {@code com.best.ai_orchestrator}。走
 * {@code META-INF/spring/...AutoConfiguration.imports} 才能让任何依赖它的服务直接拿到 Bean。
 * （和 {@code ai-common} / {@code ai-memory} 的做法一致。）
 *
 * <p>★ <b>依赖都是"必须有"的，不加降级分支</b>：DataSource（MCP 工具目录要读库）、
 * 模型登记、Redis（暂停状态要持久化）。
 * "编排器建不出来"应该表现为<b>启动失败</b>，而不是运行期悄悄不工作。
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class AiOrchestratorAutoConfiguration {

    @Value("${spring.application.name:orchestrator}")
    private String applicationName;

    // ---- 三个 AiService ----
    // ★ 都没有 .tools(...)：工具由 WorkerExecutor 执行，否则审批来不及

    @Bean
    @ConditionalOnMissingBean
    public Planner planner(ChatModelRegister chatModelRegister) {
        return AiServices.builder(Planner.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerAgent workerAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(WorkerAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public Synthesizer synthesizer(ChatModelRegister chatModelRegister) {
        return AiServices.builder(Synthesizer.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    // ---- 角色表 ----

    /**
     * 角色表的<b>兜底</b>：没人提供 {@code Map<String, AgentRole>} Bean 时给一个空的。
     *
     * <p>★ <b>角色不住在配置文件里，也不住在框架里</b> —— 它是<b>业务知识</b>
     * （有哪些专家、各自能干什么、注意什么），由使用方在自己的模块里定义成
     * {@code RoleRules} 实现 + 一个枚举/工厂，然后注入这个 Map。
     * 放 yml 的三个害处：改提示词不走 code review、多行文本编辑体验差、
     * {@code mcp-client-key} 拼错不报错。
     *
     * <p>这里给空 Map 而不是抛异常：**"忘了提供角色"应该由 {@link Orchestrator}
     * 的构造校验报出来**（错误信息更具体），自动装配类里抛异常只会得到一句
     * "Bean creation failed"，排查时对不上是哪件事。
     */
    @Bean
    @ConditionalOnMissingBean
    public Map<String, AgentRole> agentRoles() {
        log.warn("没有任何模块提供角色表（Map<String, AgentRole> Bean）—— "
                + "角色是业务知识，请在业务模块里定义 RoleRules 实现并注册。"
                + "编排器会在启动时因缺少角色而失败。");
        return new LinkedHashMap<>();
    }

    // ---- 记忆 ----

    @Bean
    @ConditionalOnMissingBean
    public MemoryAccess memoryAccess(ChatMemoryProvider chatMemoryProvider,
                                     MemoryRecallService recallService,
                                     UserMemoryRecorder recorder,
                                     OrchestratorProperties props) {
        return new MemoryAccess(chatMemoryProvider, recallService, recorder, props.getConversationTurns());
    }

    // ---- 审批与派发 ----

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGate approvalGate(OrchestratorProperties props) {
        // 不认识的风险等级会按 HIGH 处理（更严格），不会让服务起不来
        return ApprovalGate.threshold(ToolRisk.parse(props.getApprovalMinRisk()));
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerExecutor workerExecutor(Map<String, AgentRole> agentRoles,
                                        WorkerAgent workerAgent,
                                        McpClientRegister mcpClientRegister,
                                        McpToolCatalog mcpToolCatalog,
                                        ApprovalGate approvalGate) {
        return new WorkerExecutor(agentRoles, workerAgent, mcpClientRegister, mcpToolCatalog, approvalGate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskDispatcher taskDispatcher(WorkerExecutor workerExecutor, OrchestratorProperties props) {
        return new TaskDispatcher(workerExecutor, props.getMaxParallel());
    }

    @Bean
    @ConditionalOnMissingBean
    public TerminationPolicy terminationPolicy(OrchestratorProperties props) {
        return new TerminationPolicy(props.getMaxRounds(), props.getNoProgressLimit());
    }

    // ---- 暂停状态存储 ----

    /**
     * 有 Redis 就用 Redis（正式部署）。
     *
     * <p>★ <b>两个条件注解都不能少</b>：
     * {@code @ConditionalOnClass} 保证"classpath 上没有 spring-data-redis 时，
     * 这个 @Bean 方法根本不被解析"（否则会 {@code NoClassDefFoundError}）；
     * {@code @ConditionalOnBean} 保证"有类但没有连接工厂时不创建"。
     * 这是 {@code ai-memory} 里那套范式的用法。
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean
    public RunStateStore redisRunStateStore(StringRedisTemplate redisTemplate,
                                            OrchestratorProperties props) {
        String namespace = resolveAssistantId(props);
        log.info("暂停状态使用 Redis 存储，命名空间={} ttl={}h", namespace, props.getRunTtlHours());
        return new RedisRunStateStore(redisTemplate, namespace,
                Duration.ofHours(props.getRunTtlHours()));
    }

    @Bean
    @ConditionalOnMissingBean(RunStateStore.class)
    public RunStateStore inMemoryRunStateStore() {
        log.warn("未检测到 Redis，暂停状态使用【进程内】存储 —— 单机、重启即丢、多实例不共享。"
                + "正式部署请配置 spring.data.redis.*");
        return new RunStateStore.InMemory();
    }

    // ---- 编排器 ----

    @Bean
    @ConditionalOnMissingBean
    public Orchestrator orchestrator(Planner planner,
                                     Synthesizer synthesizer,
                                     TaskDispatcher taskDispatcher,
                                     TerminationPolicy terminationPolicy,
                                     RunStateStore runStateStore,
                                     MemoryAccess memoryAccess,
                                     OrchestratorProperties props,
                                     Map<String, AgentRole> agentRoles) {
        return new Orchestrator(planner, synthesizer, taskDispatcher, terminationPolicy,
                runStateStore, memoryAccess, props, agentRoles);
    }

    /** 没配 {@code ai.orchestrator.assistant-id} 就用服务名 —— 服务名唯一时这项可以不配 */
    private String resolveAssistantId(OrchestratorProperties props) {
        String configured = props.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }
}
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/OrchestratorApplication.java`

> ⚠️ **此文件已不存在** —— 启动类已按 44 号搬到 demo 模块。代码保留备查，但**不要**再往 ai-orchestrator 里建它。

```java
// （源码见 ai-orchestrator-demo 模块）
```

#### `ai-orchestrator/src/main/java/com/best/ai_orchestrator/controller/OrchestratorController.java`

> ⚠️ **此文件已不存在** —— Controller 已按 44 号搬到 demo 模块。代码保留备查，但**不要**再往 ai-orchestrator 里建它。

```java
// （源码见 ai-orchestrator-demo 模块）
```

---

# 第二部分 · 检查点与验收

## 8. 检查点命令

```bash
# ① 编译（敲完每批跑一次；出错只会在最新一批里）
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
mvn -o -DskipTests -pl ai-orchestrator -am clean compile     # 期望 BUILD SUCCESS

# ② 启动前确认配置真的被读到（40 号的教训：默认值 == 你原来的值 → 肉眼对比 yml 发现不了）
#    把 application.yml 里 checkpoint/暂停状态 ttl 改成一个明显值（如 48），
#    启动日志应出现 ttl=48h；改不生效说明 @ConfigurationProperties 没绑定上

# ③ 一次单轮请求（不需要工具、不需要审批，先验证主干）
curl -s localhost:8080/chat -H 'Content-Type: application/json' \
     -d '{"userId":"u1","sessionId":"s1","text":"帮我看看订单 12345 的状态"}' | head -c 400

# ④ 高危工具 → 必须停在审批（期望 status=waiting_approval，且工具没有执行）
#    先看日志有没有 "运行暂停 runId=... 等待审批 task=... tool=..."

# ⑤ 批准 → 工具执行一次（期望执行次数为 1，不是 2）
curl -s localhost:8080/approve -H 'Content-Type: application/json' \
     -d '{"runId":"<上一步的runId>","taskId":"<pending里的taskId>","approved":true}'

# ⑥ 连续两次高危 → 第二次必须仍然 waiting_approval（证明审批不绕过）
#    同一 sessionId 再发一次高危请求，若返回 done 说明 PendingApproval 的重置漏了
```

## 9. 九个验收用例（按"能不能一票否决"排序）

| # | 用例 | 通过标准 | 不过说明什么 |
|---|---|---|---|
| 1 | 编译 + 启动 | `BUILD SUCCESS`，无 `BeanCreationException` | 装配断了 |
| 2 | 单轮无工具请求 | `status=done`，答案非空 | 主干不通，后面全免谈 |
| 3 | **多轮对话** | 第二轮「那它什么时候发货」能正确指代 | ★ `conversationText` 没接上 |
| 4 | **高危 → 审批** | `waiting_approval`，日志有"运行暂停"，**库里工具没执行** | 审批是摆设 |
| 5 | **批准后执行一次** | `工具执行成功` 日志 **1 条** | 重复执行（30 号同类） |
| 6 | **连续两次高危** | 第二次仍 `waiting_approval` | ★ 审批可被绕过（30 号那个静默漏洞） |
| 7 | **拒绝后** | `status=done`，答案里说明"操作被拒绝" | 拒绝分支漏了（31 号同类） |
| 8 | **重启后 resume** | 重启服务 → 同一个 runId 还能 approve | Redis 实现没生效 |
| 9 | **隔离证明** | 两个子任务各写各的，互不污染 | 上下文隔离失效 |

**第 6 条是唯一能证明"审批不被绕过"的测试** —— 单次请求无论怎么测都是绿的。
**第 3 条是唯一能证明"多轮对话"的测试** —— 单轮也完全看不出问题。

## 10. 自查清单

- [ ] `mvn -o -DskipTests -pl ai-orchestrator -am clean compile` → BUILD SUCCESS
- [ ] 启动日志出现 `专家数` / 角色注册相关的信息，没有 `BeanCreationException`
- [ ] 父 pom `<modules>` 里 **没有** `ai-graph` / `ai-multiagent`，有 `ai-orchestrator`
- [ ] `grep -rn "com.best.multiagent\|com.best.ai_graph" ai-orchestrator/src/` → **0 行**（框架不认识业务）
- [ ] `grep -rn "::" ai-orchestrator/src/main/java/com/best/ai_orchestrator/memory/MemoryAccess.java` → 只出现在 `SessionKey` 相关的注释里，**没有手拼 memoryId 的代码**
- [ ] 用例 1~9 全过（见第 9 节）

---

# 第三部分 · 已知限制与后续

## 11. 现在就知道的五条限制

| # | 限制 | 影响 | 触发后再做的条件 |
|---|---|---|---|
| 1 | token 是估算值（`chars/2`） | 预算刹车偏粗 | MCP 侧开始返回 usage |
| 2 | `thought` 只进日志、不参与决策 | ReAct 偏 "Act 循环" | 模型能力/成本允许时再强制 |
| 3 | 内存 `RunStateStore` 重启即丢 | 多实例/重启丢审批 | 已给 Redis 实现，默认切它即可 |
| 4 | 单次审批只回答一个 taskId | 同批两个审批要批两次 | **这是刻意的** —— 一把全批会把第二个也放过去 |
| 5 | 循环迭代的触发条件是"每轮都问" | refine 多花一次模型调用 | 出现"refine 老是返回空"的日志再优化 |
| 6 | **② 档能力没有独立出口**（见下） | 单 agent 助手要审批，得拖走整个编排器 | ★ 见下 |

### 11.1 第 6 条要展开说 —— 它是与 41 号三档架构图的**已知偏离**

41 号架构图里，编排层是**三档独立能力**：① 工作流（纯 Java）/ ② Agent 循环（ReAct + 中断）/ ③ Orchestrator（子 agent 并行 + 隔离）。本模块把 ② 的能力**吸收进了内部**：ReAct 在 `WorkerExecutor`、中断审批也在那里 —— ② 没有独立出口。

**根因是讨论演化，不是执行走样**：后来明确要「可中断可暂停可恢复」（按图属 ② 档），范围从 9 项扩到 12 项时，② 的能力全进来了。

**现在不拆是对的** —— 对 Orchestrator 来说，子 agent 内部跑 ReAct + 审批是**正确结构**，拆开反而多一层。

**★ 拆出的触发条件（写下来，免得半年后重新纠结）**：

> 出现第二个「**需要审批 / 可暂停可恢复，但不做多 agent 编排**」的使用者时。
> 最现实的候选：`assistant-demo`（单 agent 助手）哪天接一个危险工具（删数据、发消息）需要审批 ——
> 而现在的审批绑在 `WorkerExecutor` 的"子任务执行"上下文上，它要用，得把整个编排器拖回家。

**候选抽法**：`ApprovalGate` 已经是个接缝（本模块里它就是接口）；要抽的是 `WorkerExecutor` 里「提议 → 查 risk → 暂停」那段判断。

**顺带纠正 41 号图的两处描述**（别再按它规划）：
① 档「工作流」**本就不该有模块** —— 纯 Java 方法即工作流，画个框反而让人以为"这里会有一个东西"；
② 档「**图** + 中断 + checkpoint」的"图"字过时 —— 已论证不用 langgraph4j（本单第 2 节），准确描述是「循环 + 中断 + 可恢复」。

## 12. 本模块刻意不做的事

1. **不做通用图引擎**（第 2 节）：触发条件是「同一份状态，走不同拓扑」。
2. **不做 expert↔工具映射表**：越权在网关层拦（29 号结论），本地再维护一份必然漂移。
3. **不把 `RunStateStore` 推给调用方**：收在编排器内部，"忘了存"拿不到机会。
4. **不加"可选功能开关"**：`withMemory` / `withTools` 这类参数一旦开始，就会走向"逃生舱"，
   而逃生舱就是手写流程 —— 36 号那条"总是建"的结论在这里同样适用。
5. **不抽 `ai-graph`**：使用者只有 1 个时不该抽（34 号判据）。

## 13. 与旧模块的关系

`ai-graph` / `ai-multiagent` **已确认弃用**。它们留下三样东西仍在本模块里活着：

- **13 条陷阱清单**（35 号）→ 已织进本单的验收用例
- **子图方案的否决实证**（`docs/probes/SubGraphProbe.java`）→ 第 2 节的证据
- **`InterruptibleAction` 的 javap 结论**（29 号）→ 第 6 节"工具不挂 AiServices"的依据

---

## 14. 序列化往返探针（"恢复链路可用"的书面证据）

**文件**：`docs/probes/JacksonProbe2.java`（与 `SubGraphProbe.java` 并列 —— 后者证"为什么不用子图"，本探针证"暂停/恢复真的能用"）。

**它测什么**：11 个类型逐个做 `序列化 → 反序列化 → 断言字段`，覆盖恢复链路的全部构件：

```
Budget · TaskOutcome(done/failed) · WorkerTurn · PendingApproval · RunEvent
TaskNode(待审批态) · TaskNode(已完成态) · Plan · RunState(总装) · 再序列化
```

**怎么跑**（一次性 Java 脚本，不需要 Maven 测试插件）：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
CP="ai-orchestrator/target/classes:ai-common/target/classes:ai-memory/target/classes"
CP="$CP:$(find ~/.m2/repository/com/fasterxml/jackson -name 'jackson-databind-2.21.4.jar')"
CP="$CP:$(find ~/.m2/repository/com/fasterxml/jackson -name 'jackson-core-2.21.4.jar')"
CP="$CP:$(find ~/.m2/repository/com/fasterxml/jackson -name 'jackson-annotations-2.21.jar')"
CP="$CP:$(find ~/.m2/repository/com/fasterxml/jackson -name 'jackson-datatype-jsr310-2.21.4.jar')"
CP="$CP:$(find ~/.m2/repository/dev/langchain4j -name 'langchain4j-core-1.20.0.jar' | head -1)"
CP="$CP:$(find ~/.m2/repository/dev/langchain4j -name 'langchain4j-1.20.0.jar' | head -1)"
CP="$CP:$(find ~/.m2/repository/org/slf4j -name 'slf4j-api-2.0.18.jar' | head -1)"
mvn -o -DskipTests -q clean compile && $JAVA_HOME/bin/java -cp "$CP" docs/probes/JacksonProbe2.java
# 期望：11 行 ✓ + ★★★ 全部通过 ★★★
```

**★ 什么时候必须重跑**：改 `core/` 里任何字段、加减 `RunEvent` 字段、动 `RedisRunStateStore` 之后。
它抓的正是"编译通过但恢复断裂"那一类 bug —— 第 0.5 节那 3 个断点全是它抓出来的。

**踩坑记录**（写在这里，免得下次重新踩）：
- `.m2` 里有多个 jackson 小版本，classpath 里混进 2.15.x 会和 Boot 的 2.21.4 行为不一致 → **按版本精确挑 jar**
- 探针的 mapper 配置必须和 `RedisRunStateStore` 里的 `JSON` 常量**逐字一致**（注册 JavaTimeModule + 关 WRITE_DATES_AS_TIMESTAMPS）—— 探针测的是"生产配置下的行为"，不是"某个顺手配置下的行为"
