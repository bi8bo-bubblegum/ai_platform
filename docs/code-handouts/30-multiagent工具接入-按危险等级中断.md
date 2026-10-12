# 30 · multiagent 工具接入（第 2 步）：按危险等级中断

> **前置**：必须先敲完 `29-multiagent工具接入-清单与执行.md`（第 1 步）。本单是在它的基础上加东西。
>
> **本步要达成的效果**：低危工具直接执行；**高危工具停下来等人批准**；人批准后才真正执行。
>
> ⚠️ **29 号里有一处说明是错的，本单已修正** —— 见第 0 节。

---

## 0. 先修正 29 号的一处错误

29 号第 4.13 节的三点说明里，第 1 点原话是：

> ~~"`toolCatalog` 参数其实没用上……**请删掉那个直接的 `McpToolCatalog toolCatalog` 参数**，只留 `ObjectProvider`"~~

**这是错的。** 正确做法是**直接注入，删掉 `ObjectProvider` 那个参数**。理由：

`ai-multiagent` **本来就离不开数据库** —— 长期记忆写 `ai_user_memory`、token 台账写 `ai_token_usage`，
而 `spring.datasource.url` 已经指向 `mcp_gateway` 库。
**"没有数据库的 ai-multiagent"这个场景根本不存在**，为一个不存在的场景加一层 `ObjectProvider`，
代价是每个节点里到处 `getIfAvailable()` + null 判断。

> 29 号文档里那一段**已经改过来了**，你照改后的版本敲即可。

**所以本单的两个节点都用直接注入的 `McpToolCatalog`。**

---

## 1. 本步范围

| # | 动作 | 文件 |
|---|---|---|
| 1 | **重写** | `ai-multiagent/.../node/ToolExecuteNode.java`（`interrupt()` 从恒空改成按 risk 判断） |
| 2 | 新增 | `ai-multiagent/.../graph/GraphNodes.java`（节点名的唯一来源） |
| 3 | 改造 | `ai-multiagent/.../config/MultiAgentGraphConfig.java`（读阈值 + 传参 + 节点名用常量） |
| 4 | 改造 | `ai-multiagent/.../controller/AgentController.java`（审批响应体 + resume 带标记） |
| 5 | 改造 | `ai-multiagent/src/main/resources/application.yml`（加一个配置项） |
| 6 | 删除 | `ai-multiagent/.../node/ApprovalGateNode.java`（职责已被 `ToolExecuteNode` 完全接管） |

**图结构不动、state SCHEMA 不动、ai-common 不动。** 这就是第 1 步先把拓扑定稿的价值。

---

## 2. ★ 本单最重要的一个点：`approvalDecided` 必须被重置

这不是"优化"，是**正确性的一部分**。请先看懂再敲。

**`approvalDecided` 的语义必须是「当前这个提议已经被人回答过」，不是「这个人曾经回答过」。**

如果搞错了（比如只在 `/approve` 时写 true、从不重置），会发生这个：

```
第一轮  用户："删除上个月的测试数据"
        → expert 提议 mysql__mysql_query
        → interrupt() 查 risk=high → 停下 → 用户批准 → 执行
        → approvalDecided 仍然是 true

第二轮  用户："再把生产库的备份也删了"
        → expert 提议 mysql__mysql_query
        → interrupt() 看到 approvalDecided=true → 【直接放行】
        → 危险操作在完全没有被审批的情况下执行了
```

**这个漏洞是静默的** —— 日志上只看到"工具执行成功"，
要对比"这次有没有停下来问"才发现。所以重置动作写在两个地方：

| 位置 | 作用 |
|---|---|
| `ToolExecuteNode.finish()` | 每次处理完一个提议就重置 → **保证同一轮对话里的第二次危险提议仍会被拦** |
| `AgentController` 的 `/chat` 初始化 | 防御性重置 → 保证每个新请求从干净状态开始 |

**两个都要**。只做前者，万一哪天有路径没走到 `finish()`，脏标记会跨请求残留；
只做后者，同一轮里的第二次危险操作就漏了。

---

## 3. 代码

### 3.1 `ai-multiagent/src/main/java/com/best/multiagent/graph/GraphNodes.java`（新增）

```java
package com.best.multiagent.graph;

/**
 * 图里固定节点的名字。
 *
 * <p><b>为什么值得单独一个类。</b> 节点名有两个使用方，而且它们<b>必须一致</b>：
 * <ul>
 *   <li>{@code MultiAgentGraphConfig} —— 建图时 {@code addNode(name, ...)}</li>
 *   <li>{@code AgentController} —— 判断"这次是不是停在中断点上等人批准"</li>
 * </ul>
 * 原来这两处各写一遍字符串 {@code "approvalGate"}，改名时必漏一处，
 * 而漏了的症状是<b>"审批流程静默失效"</b>（Controller 永远判不出"停在闸门上"，
 * 就把等待中的请求当成"已完成"返回给前端）。
 *
 * <p>这和 {@link GraphThreadIds} 是同一类东西 —— <b>凡是"必须两处一致"的字符串，
 * 都要有唯一来源</b>。项目里已经吃过三次这种亏（{@code SUMMARY_PREFIX} 少冒号、
 * memoryId 少冒号、{@code "approvalGate"} 两处定义）。
 *
 * <p><b>这里只放固定的节点名。</b> 专家节点的名字来自
 * {@code SupervisorAgent.Routing} 的枚举值（{@code ORDER/ANALYSIS/KNOWLEDGE}），
 * 是动态的、且有它自己的唯一来源（枚举本身），不重复放在这里。
 */
public final class GraphNodes {

    /** 长期记忆召回 */
    public static final String RECALL_MEMORY = "recallMemory";

    /** 调度器 */
    public static final String SUPERVISOR = "supervisor";

    /** 工具执行 —— <b>全图唯一的中断点</b> */
    public static final String TOOL_EXECUTE = "toolExecute";

    /** 长期记忆写入 */
    public static final String WRITE_MEMORY = "writeMemory";

    private GraphNodes() {
    }
}
```

### 3.2 `ai-multiagent/.../node/ToolExecuteNode.java`（**完整替换**）

```java
package com.best.multiagent.node;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具执行节点 —— 全图唯一真正产生副作用的地方，也是唯一的中断点。
 *
 * <p><b>本类的核心不变式（改代码时不要破坏它）</b>：
 * <ol>
 *   <li>{@link #interrupt} 在节点执行<b>之前</b>被框架调用。返回 {@code Optional.of(...)}
 *       时<b>本节点的 {@link #apply} 根本不会执行</b> —— 所以"停下的那一刻，工具真的没被调用"。
 *       这是整个审批机制唯一有意义的地方。</li>
 *   <li>"该不该执行"的判断<b>只在 {@link #interrupt} 里做一次</b>。
 *       {@link #apply} 被调用时，框架已经问过并得到"可以执行"的答复 ——
 *       在 apply 里再判一次就是第二份真相。</li>
 *   <li>每个提议处理完，必须经过 {@link #finish} 收尾（清提议 + 重置已决标记）。</li>
 * </ol>
 */
@Slf4j
public class ToolExecuteNode implements NodeAction<MultiAgentState>, InterruptibleAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private final McpClientRegister register;

    /** 查危险等级用。直接注入 —— ai-multiagent 本来就依赖数据库，见 30 号第 0 节 */
    private final McpToolCatalog toolCatalog;

    /** 达到或超过这个等级才拦下来问人。来自 {@code ai.multiagent.approval-min-risk} */
    private final ToolRisk approvalMinRisk;

    public ToolExecuteNode(McpClientRegister register,
                           McpToolCatalog toolCatalog,
                           ToolRisk approvalMinRisk) {
        this.register = register;
        this.toolCatalog = toolCatalog;
        this.approvalMinRisk = approvalMinRisk;
    }

    /**
     * 判断这次工具调用要不要停下来等人批准。
     *
     * <p><b>四条分支的顺序是有讲究的，不要重排</b>：
     * 先排除"没有提议"，再排除"已经问过了"，最后才是"查等级比阈值"。
     * 把②放到③后面的话，一个已批准的提议会被再查一次等级（多一次无意义的读缓存），
     * 而更重要的是可读性 —— 这四个分支正好对应四种情况。
     */
    @Override
    public Optional<InterruptionMetadata<MultiAgentState>> interrupt(String nodeId,
                                                                    MultiAgentState state,
                                                                    RunnableConfig config) {
        String tool = state.proposedTool();

        // ① 没有提议 —— 纯问答/解释类请求。这是最常见的情况，直接放行
        if (isNoTool(tool)) {
            return Optional.empty();
        }

        // ② 这次提议已经被人回答过了（批或拒）—— 放行，由 apply() 去决定走哪个分支。
        //    没有这一条，批准之后图会在同一个地方<b>再中断一次</b>，
        //    表现为"前端点了批准但什么都没发生"
        if (state.approvalDecided()) {
            return Optional.empty();
        }

        // ③ 查危险等级。查不到的一律 HIGH（表里没这个工具、risk 值是没见过的、目录加载失败），
        //    所以"查不到"的默认行为是"要审批"而不是"放行"
        ToolRisk risk = toolCatalog.riskOf(state.proposedBy(), tool);

        // ④ 未达阈值 —— 直接执行
        if (!risk.atLeast(approvalMinRisk)) {
            log.info("工具 {} 危险等级={} 未达审批阈值={}，直接执行", tool, risk, approvalMinRisk);
            return Optional.empty();
        }

        // ⑤ 达到阈值 —— 停在这里。本节点的 apply() 不会执行，工具不会被调用
        log.info("工具 {} 危险等级={} 达到审批阈值={}，暂停等待人工批准", tool, risk, approvalMinRisk);
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .reason("工具 " + tool + " 危险等级为 " + risk + "，需要人工批准")
                .build());
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String tool = state.proposedTool();
        if (isNoTool(tool)) {
            return Map.of();
        }

        // 被拒绝：不执行，回写一条说明让模型知道"这个动作被人挡下来了"。
        //
        // ★ 判据是两个条件的【与】：approvalDecided && !approved
        //   只看 !approved 会误判 —— 低危工具从来没问过人，approved 也是默认的 false，
        //   那样所有低危工具都会被当成"被拒绝"
        if (state.approvalDecided() && !state.approved()) {
            log.info("工具 {} 被人工拒绝", tool);
            return finish(AiMessage.from("[操作被拒绝] 已取消：" + tool
                    + "。如果用户另有需要，可以改用别的方式帮他。"));
        }

        McpClient client = register.get(state.proposedBy());
        if (client == null) {
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", state.proposedBy(), tool);
            return finish(AiMessage.from("[工具 " + tool + " 未能执行] 未配置 "
                    + state.proposedBy() + " 的 MCP 通道"));
        }

        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    // MCP JSON-RPC 的关联标识。不显式给的话某些服务端会拒绝
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(state.proposedArgs())
                    .build();

            ToolExecutionResult result = client.executeTool(request);

            // isError() 是"工具正常返回了但业务上失败"（比如 SQL 语法错），
            // 和"网络异常抛异常"是两回事。区分开写，模型才不会把错误信息当成查询结果用
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, state.proposedBy(), abbreviate(text));

            return finish(AiMessage.from("[" + (failed ? "工具 " + tool + " 返回错误"
                    : "工具 " + tool + " 结果") + "]\n" + text));
        } catch (Exception e) {
            // 工具失败不能崩图 —— 把失败当成一次"工具结果"写回消息，
            // 模型下一轮能看到失败原因并改用别的方式回答
            log.error("工具执行异常: tool={} by={} args={}",
                    tool, state.proposedBy(), state.proposedArgs(), e);
            return finish(AiMessage.from("[工具 " + tool + " 执行失败] " + e.getMessage()));
        }
    }

    /**
     * 统一的收尾动作。<b>所有从 {@link #apply} 出去的路径都必须经过这里。</b>
     *
     * <p>它做两件事，缺一不可：
     * <ol>
     *   <li><b>清空提议</b> —— 不清的话，`supervisor` 下一轮会把同一个提议当成新的，
     *       于是同一个工具被重复执行。</li>
     *   <li><b>重置 {@code approvalDecided}</b> —— 见下一段。这是本方案最容易漏、
     *       后果最严重的一个点。</li>
     * </ol>
     *
     * <p><b>为什么必须重置：</b>
     * <pre>
     * 不重置的话 ——
     *   第一轮  "删测试数据" → risk=high → 停下 → 批准 → 执行（approvalDecided 仍为 true）
     *   第二轮  "再删生产备份" → risk=high → interrupt() 看到 approvalDecided=true
     *                          → 直接放行 → 【危险操作完全没被审批就执行了】
     * </pre>
     * 而且这个漏洞是<b>静默</b>的：日志上只有"执行成功"，
     * 要对比"这次有没有停下来问"才能发现。
     *
     * <p><b>正确语义</b>：{@code approvalDecided} = "当前这个提议已经被人回答过"，
     * 不是"这个人曾经回答过"。所以每处理完一个提议就要把它复位。
     */
    private Map<String, Object> finish(AiMessage message) {
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.PROPOSED_TOOL, "",
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    private static boolean isNoTool(String tool) {
        return tool == null || tool.isBlank() || NO_TOOL.equals(tool);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...(截断)";
    }
}
```

### 3.3 `ai-multiagent/.../config/MultiAgentGraphConfig.java`（改造）

**四处改动**（其余保持 29 号的样子）：

```java
// ===== 改动 1：import 换成 GraphNodes（不再用本类里的字符串常量声明节点名）=====
import com.best.multiagent.graph.GraphNodes;

    // ===== 改动 2：删掉本类里的 NODE_TOOL_EXECUTE 常量声明 =====
    // 原来这里有一行：
    //     private static final String NODE_TOOL_EXECUTE = "toolExecute";
    // 现在改用 GraphNodes.TOOL_EXECUTE（唯一来源）

    // ===== 改动 3：方法签名 —— 删掉 ObjectProvider，加一个阈值参数 =====
    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            ExpertAgent expertAgent,
            MemoryRecallService recallService,
            UserMemoryRecorder memoryRecorder,
            McpToolCatalog toolCatalog,                              // ← 直接注入（29 号第 0 节）
            McpClientRegister mcpRegister,
            @Value("${ai.multiagent.assistant-id:${spring.application.name:multiagent}}")
            String assistantId,
            // 审批阈值。默认 high —— 也就是"只有高危工具才问人"。
            // 想更严就填 medium（中等以上都要问），填 low 会让所有工具都问，没有意义
            @Value("${ai.multiagent.approval-min-risk:high}") String approvalMinRiskRaw,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${ai.multiagent.checkpoint-ttl-hours:24}") long ttlHours
    ) throws GraphStateException {

        // 用 ToolRisk.parse 解析配置值 —— 它"未知值一律按 HIGH"的语义正好也用在这里：
        // 配置里写成 "critical" 这种错值时，结果是【更严格】而不是更宽松。
        // 用 valueOf 的话会抛 IllegalArgumentException 让服务起不来，
        // 一个配置拼写错误不该是启动失败
        ToolRisk approvalMinRisk = ToolRisk.parse(approvalMinRiskRaw);
        log.info("工具审批阈值: {}", approvalMinRisk);

        // ... saver 那一段不变 ...

        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(supervisorAgent)))
                .addNode(AGENT_ORDER, node(new ExpertNode("订单助手", AGENT_ORDER, expertAgent, toolCatalog)))
                .addNode(AGENT_ANALYSIS, node(new ExpertNode("数据分析助手", AGENT_ANALYSIS, expertAgent, toolCatalog)))
                .addNode(AGENT_KNOWLEDGE, node(new ExpertNode("知识库助手", AGENT_KNOWLEDGE, expertAgent, toolCatalog)))
                // ===== 改动 4：执行节点换成带审批的新构造 =====
                .addNode(GraphNodes.TOOL_EXECUTE,
                        node(new ToolExecuteNode(mcpRegister, toolCatalog, approvalMinRisk)))
                .addNode(GraphNodes.WRITE_MEMORY, node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, GraphNodes.RECALL_MEMORY)
                .addEdge(GraphNodes.RECALL_MEMORY, GraphNodes.SUPERVISOR)
                .addConditionalEdges(GraphNodes.SUPERVISOR,
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        Map.of(AGENT_ORDER, AGENT_ORDER,
                                AGENT_ANALYSIS, AGENT_ANALYSIS,
                                AGENT_KNOWLEDGE, AGENT_KNOWLEDGE,
                                "FINISH", GraphNodes.WRITE_MEMORY))
                .addEdge(AGENT_ORDER, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_ANALYSIS, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_KNOWLEDGE, GraphNodes.TOOL_EXECUTE)
                .addEdge(GraphNodes.TOOL_EXECUTE, GraphNodes.SUPERVISOR)
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);

        // ⚠️ 这里【依然不要】配 interruptBefore。
        // 它是编译期的无条件中断，表达不了"按危险等级决定"。
        // 条件中断完全由 ToolExecuteNode 的 InterruptibleAction 负责。
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(25)
                .build());
    }
```

> `import com.best.ai_common.mcp.ToolRisk;` 也要补上。

### 3.4 `ai-multiagent/.../controller/AgentController.java`（改造）

```java
// 新增 import
import com.best.multiagent.graph.GraphNodes;
// 删掉原来的 private static final String GATE = "approvalGate";

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        Map<String, Object> input = new HashMap<>();
        input.put(MultiAgentState.MESSAGES, List.of(UserMessage.from(req.message())));
        input.put(MultiAgentState.USER_ID, userId == null ? "" : userId);
        input.put(MultiAgentState.SESSION_ID, req.sessionId);
        input.put(MultiAgentState.PROPOSED_TOOL, "");
        input.put(MultiAgentState.APPROVED, false);
        // 防御性重置：每个新请求都从"没有待决提议"开始。
        // 正常路径下 finish() 已经把它复位了，这里再写一次是防止
        // 将来有路径没走到 finish() 时脏标记跨请求残留
        input.put(MultiAgentState.APPROVAL_DECIDED, false);

        return run(() -> graph.invoke(GraphInput.args(input), config), threadId, config);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req) {
        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        log.info("收到审批: threadId={} approved={}", threadId, req.approved());

        return run(() -> graph.invoke(GraphInput.resume(Map.of(
                        MultiAgentState.APPROVED, req.approved(),
                        // ★★ 必须同时写这个标记。只写 approved 的话，
                        //     interrupt() 里"是否已经问过"仍然读到 false，
                        //     图会在同一个地方再中断一次 —— 表现为"点了批准但没反应"
                        MultiAgentState.APPROVAL_DECIDED, true)),
                config),
                threadId, config);
    }

    private Map<String, Object> respond(MultiAgentState state, String threadId, RunnableConfig config) {
        // 节点名从 GraphNodes 取 —— 全项目唯一来源。
        // 原来这里是 GATE（"approvalGate"），那个节点已经不存在了
        if (GraphNodes.TOOL_EXECUTE.equals(nextNodeOf(config))) {
            return waiting(threadId, config);
        }
        return Map.of(
                "status", "done",
                "threadId", threadId,
                "answer", lastAiText(state.messages())
        );
    }

    /**
     * 等待审批时的响应体。
     *
     * <p><b>为什么要把参数也带上。</b> 前端要展示"请你确认要执行什么" ——
     * 光有工具名（{@code mysql__mysql_query}）用户看不懂，得看到参数
     * （比如 {@code {"sql": "DELETE ..."} }）才知道自己批的是什么。
     * 这是审批 UI 能不能做得有意义的前提。
     *
     * <p><b>危险等级没有放在这里</b>：工具名+参数已经足够表达"要做什么"，
     * 而等级对用户是内部概念。如果你希望前端按等级显示不同的警示强度，
     * 扩展点是在 {@code ExpertNode} 写提议时把 risk 一并写进 state 的一个新 channel，
     * 然后在这里读出来 —— 不要在 Controller 里注入 {@code McpToolCatalog} 现查，
     * 那会让"响应格式"依赖"目录加载状态"，两种失败原因混在一起。
     */
    private Map<String, Object> waiting(String threadId, RunnableConfig config) {
        StateSnapshot<MultiAgentState> snapshot = graph.getState(config);
        MultiAgentState state = snapshot == null ? null : snapshot.state();
        if (state == null) {
            return Map.of("status", "waiting_approval", "threadId", threadId);
        }
        return Map.of(
                "status", "waiting_approval",
                "threadId", threadId,
                "pendingAction", state.proposedTool(),
                "pendingArgs", state.proposedArgs()
        );
    }
```

### 3.5 `ai-multiagent/.../node/ApprovalGateNode.java`（删除）

**可以直接删掉这个文件** —— 它的职责（"在执行前把图停下来"）已经完全由
`ToolExecuteNode` 的 `InterruptibleAction` 接管，而且接管得更正确：

| | 原来的 `ApprovalGateNode` + `interruptBefore` | 现在的 `ToolExecuteNode` |
|---|---|---|
| 什么时候停 | **无条件**，所有走这条边的都会停 | **按危险等级**，低危直接过 |
| 工具执行了吗 | 没有（它是假执行，只往消息里写"已执行"） | 真执行 |
| 停下来了谁知道 | 不知道，checkpoint 里没记录原因 | `InterruptionMetadata.reason()` 里写了工具名和等级 |

删之前确认一下：`grep -rn "ApprovalGateNode\|approvalGate" ai-multiagent/src/` 应该是 0 命中。

### 3.6 `ai-multiagent/src/main/resources/application.yml`（改造）

```yaml
ai:
  multiagent:
    assistant-id: ai-multiagent
    checkpoint-ttl-hours: 24
    # 达到或超过这个危险等级的工具，需要人工批准后才执行。
    # 可选值: low / medium / high
    #   high（默认）→ 只有高危工具（如 mysql__mysql_query）会停下问人
    #   medium      → 中危以上都问
    #   low         → 所有工具都问，包括纯查询 —— 通常没有意义
    # ⚠️ 写了一个不认识的值（比如 critical）不会启动失败，
    #    会按 HIGH 处理并打一条 warn —— 即"更严格"，不是"更宽松"
    approval-min-risk: high
```

---

## 4. 验收

### 4.1 编译 + 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

启动日志里应该有这一行：

```
工具审批阈值: HIGH
```

### 4.2 低危工具 —— 应该直接执行，不停

```bash
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"帮我查一下订单 12345 的状态","sessionId":"s-low"}'
# 期待: {"status":"done", ...}   ← 直接done，没有 waiting_approval
```

日志期待：

```
专家 订单助手 提议工具: order__searchMultipartBizOrder 参数: {...}
工具 order__searchMultipartBizOrder 危险等级=LOW 未达审批阈值=HIGH，直接执行
工具执行成功: tool=order__searchMultipartBizOrder by=order result=...
```

### 4.3 高危工具 —— 应该停下来

```bash
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"查一下数据库里有多少条订单记录","sessionId":"s-high"}'
# 期待: {"status":"waiting_approval","threadId":"s-high",
#         "pendingAction":"mysql__mysql_query","pendingArgs":"{...}"}
```

日志期待：

```
专家 数据分析助手 提议工具: mysql__mysql_query 参数: {...}
工具 mysql__mysql_query 危险等级=HIGH 达到审批阈值=HIGH，暂停等待人工批准
```

**★ 关键验证：此时工具**没有**被执行。** 去数据库/日志里确认没有那次查询的痕迹。

### 4.4 批准 —— 应该执行

```bash
curl -s -X POST http://localhost:8081/agent/approve \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s-high","approved":true}'
# 期待: {"status":"done", ...}，且 answer 里有真实的数据结论
```

日志期待：

```
收到审批: threadId=s-high approved=true
工具执行成功: tool=mysql__mysql_query by=analysis result=...
```

### 4.5 ★★ 本单最重要的验证：**连续两次高危操作，第二次也必须停下来**

这是专门验第 2 节那个漏洞的。**如果这一步过了，整个机制才算对。**

```bash
# 第一次
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下数据库里有多少条订单记录","sessionId":"s-twice"}'
# → waiting_approval

curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"s-twice","approved":true}'
# → done

# 第二次：同一个会话，再来一次高危请求
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"再帮我统计一下数据库里表的总数","sessionId":"s-twice"}'
# ★ 期待: 仍然是 waiting_approval！
#   如果这里是 done —— 说明 approvalDecided 没被重置，危险操作绕过审批执行了
```

### 4.6 拒绝

```bash
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下数据库里有多少条订单记录","sessionId":"s-reject"}'
# → waiting_approval

curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"s-reject","approved":false}'
# 期待: {"status":"done", ...}，answer 里是"操作已被拒绝"这类说明
# 日志: 工具 mysql__mysql_query 被人工拒绝
# ★ 且工具没有被执行
```

---

## 5. 自查清单

**编译期**
- [ ] `GraphInterruptException` 没被 import（本单不需要它）
- [ ] `import com.best.ai_common.mcp.ToolRisk;` 已加
- [ ] `ObjectProvider` 的 import 已从 `MultiAgentGraphConfig` 删掉（改用直接注入）
- [ ] `grep -rn "approvalGate\|ApprovalGateNode" ai-multiagent/src/` → **0 命中**

**行为**
- [ ] 低危工具：日志有 `未达审批阈值=HIGH，直接执行`，且**没有** `waiting_approval`
- [ ] 高危工具：日志有 `暂停等待人工批准`，响应是 `waiting_approval`，**且工具没被执行**
- [ ] 批准后：日志有 `工具执行成功`，响应 `done`
- [ ] 拒绝后：日志有 `被人工拒绝`，响应 `done`，**且工具没被执行**
- [ ] **连续两次高危：第二次仍然 `waiting_approval`**（4.5 节，最重要的那条）

**边界**
- [ ] 把 `approval-min-risk` 改成 `low` → 连纯查询都要审批（说明阈值真的在生效）
- [ ] 把 `approval-min-risk` 写成一个错值（比如 `unknown-level`）→ 服务正常启动，
      日志有一行 `未知 risk 值: [unknown-level] → 按最严等级 HIGH 处理`

---

## 6. 下一步（第 3 步预告）

你确认要走子图，那么第 3 步做这件事：**把每个 expert 变成子图**，
循环体从"图的上层"收进"expert 内部"，supervisor 退化为纯跨专家路由。

**已核实**：`addNode(String, CompiledGraph)` 重载存在。

**动之前要先验四件事**（第 3 步的单子里会逐条给结论）：

1. `addNode(String, CompiledGraph<State>)` 是否要求子图 state 与父图**类型一致**
2. 子图的 checkpoint 由父图 saver 统一处理，还是要给子图单独 `compile(xxx)` 带 saver
3. `SubCompiledGraphNodeAction`（jar 里有这个类）是公开 API 还是框架内部用的
4. 子图会让调试多一层嵌套 —— 日志里怎么一眼看出"现在在哪个子图的哪个节点"

**第 3 步之前不要动的东西**：本单的 `ToolExecuteNode`、`GraphNodes`、`approvalDecided` 机制 ——
它们在子图里**原样适用**（因为 `interrupt()` 是节点级的，子图里的节点一样有这能力）。
