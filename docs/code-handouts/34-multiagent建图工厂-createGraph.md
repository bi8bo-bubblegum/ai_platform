# 34 · 建图工厂：`createGraph(枚举)` 一条语句建整张图

> **前置**：32 号（循环收进专家）已落地。
>
> **本单是【重构】，不是功能变更。** 验收标准是「**行为完全不变**」——
> 建出来的图和你现在手工建的那张，节点、边、条件完全相同。
>
> **收益**：新增一个 expert，从**改 4 处**变成**改 2 处**，且那 2 处都是编译期能看出来的。

---

## 0. 先说清楚这个决定的性质 —— 它不是"抽框架"

| | 本单做的事 | 抽成公共模块 |
|---|---|---|
| 形态 | 在 `ai-multiagent` 里**提取一个方法** | 新建 Maven 模块 + 公共 API |
| 依赖变化 | **零** | 新增模块 + 边界 + 版本管理 |
| 影响范围 | 就这个模块 | 所有使用者 |
| 改错了怎么办 | 改回来 | 要动公共 API |
| 何时该做 | **现在**（消除已存在的重复） | 出现 ≥2 个使用者之后 |

**它会一直住在 `ai-multiagent` 里。** 哪天这个应用的图要改形状了，**直接改它** ——
影响范围就是这个模块。它变成"有代价的框架"，是从它**被移出这个模块**那一刻开始的。

---

## 1. 它消除的是什么重复

现在加一个 expert（比如"物流助手"），要改 **4 处**：

| # | 位置 | 内容 | 漏了会怎样 |
|---|---|---|---|
| 1 | `SupervisorAgent.Routing` | 加枚举值 + `@Description` | 编译错误（穷尽 switch 会提醒）**✓ 安全** |
| 2 | `MultiAgentGraphConfig` | `.addNode("logistics", node(new ExpertNode(...)))` | **静默**：节点不存在 |
| 3 | `MultiAgentGraphConfig` | 条件边 `Map.of(...)` 加一对 | **静默**：路由不到 |
| 4 | `application.yml` | `ai.mcp.servers` 加 token | 启动 warn，该专家无工具 |

**2 和 3 是纯接线，而且漏了都不报错。** 今天就因为 1 和 3 不一致
（`edgeKey()` 返回 `"finish"`、而 Map 的 key 是 `"FINISH"`）出过一次 bug。

本单要把 2、3 变成**由枚举循环生成** —— 它们就**物理上不可能漏**。

**改完之后加一个 expert 只改 2 处**：枚举里加一行 + yml 里加一个 token。

---

## 2. 新增 `graph/ExpertRouting.java`

```java
package com.best.multiagent.graph;

/**
 * 「路由枚举」需要提供的信息。
 *
 * <p><b>为什么要有这个接口。</b> 建图工厂要 for 循环遍历枚举、为每个值建一个专家节点，
 * 它必须能回答两个问题：<b>这个值叫什么名字</b>、<b>它是不是一个专家</b>。
 *
 * <p>用接口而不是让工厂去反射读注解，是因为这两件事都<b>必须在编译期就能表达</b> ——
 * 反射读注解把错误推到了运行期。
 */
public interface ExpertRouting {

    /**
     * 边上的 key。<b>它同时是三样东西</b>：
     * 专家节点的名字、supervisor 条件边的 key、`ai.mcp.servers` 里的 key。
     *
     * <p>三者同一个值是刻意的 —— 它们本来就该一致（"这个专家"= "这条路由"= "这个 token 能看到的工具"），
     * 分开配置只会漂移。
     */
    String edgeKey();

    /**
     * 中文角色名，会写进专家的系统提示词（"你是{{role}}"）。
     *
     * <p><b>返回 {@code null} 表示"这个枚举值不是专家"</b> ——
     * 比如 {@code FINISH}，它只是 supervisor 的一个路由终点。
     * 建图循环会跳过它，但会给它建一条"指向收尾节点"的边。
     *
     * <p><b>注意它和 {@code @Description} 是两件事</b>：
     * <ul>
     *   <li>{@code @Description} → 进 <b>supervisor</b> 的提示词（"什么时候该派给我"）</li>
     *   <li>{@code expertRole()} → 进 <b>这个专家自己</b>的提示词（"你是谁"）</li>
     * </ul>
     * 一个是"别人怎么看它"，一个是"它怎么看自己"，所以不能复用同一个字符串。
     */
    String expertRole();
}
```

---

## 3. 新增 `graph/MultiAgentGraphFactory.java` ★ 本单的核心

```java
package com.best.multiagent.graph;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.node.*;
import com.best.multiagent.state.MultiAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.HashMap;
import java.util.Map;

/**
 * 建图工厂 —— <b>一条语句建出完整的多智能体图</b>。
 *
 * <p>它建的图<b>形状是固定的</b>：
 * <pre>
 *   START → recallMemory → supervisor ⇄ [propose 专家 × N] ⇄ toolExecute
 *                              │
 *                              └──(结束)──→ writeMemory → END
 * </pre>
 * <b>形状固定不是缺陷，是这个工厂的定义。</b> 它住在 `ai-multiagent` 里，
 * 哪天这个应用需要别的形状，直接改这里 —— 影响范围就这一个模块。
 *
 * <p><b>它变的是"有几个专家"</b>：由传入的路由枚举决定。
 * 加一个 expert 只需要在枚举里加一行 —— 节点、边、路由表全部自动生成，
 * 不存在"漏改一处"的可能。
 */
@Slf4j
public final class MultiAgentGraphFactory {

    /**
     * 工具执行完之后"结束循环、回调度器"的边 key。
     *
     * <p>刻意不复用 {@code Routing.FINISH.edgeKey()}：{@code FINISH} 的含义是
     * "整个任务结束"，而这里是"本轮 ReAct 结束、回去看全局"—— 两件事借用同一个名字
     * 是将来最容易读错的地方。
     */
    private static final String LOOP_END = "loopEnd";

    /** 没有提议时专家写的标记。和 {@code ExpertNode}/{@code ToolExecuteNode} 里的约定一致 */
    private static final String NO_TOOL = "NONE";

    private MultiAgentGraphFactory() {
    }

    /**
     * 建一张多智能体图。
     *
     * @param routingEnum   路由枚举。<b>每个值要么是一个专家，要么是"结束"标记</b>
     *                      （靠 {@link ExpertRouting#expertRole()} 是否为 null 区分）
     * @param assistantId   这个图的标识。同时用作 checkpoint 的 key 命名空间
     * @param saver         checkpoint 存储。<b>由调用方建好传进来</b> ——
     *                      因为它的参数（Redis 地址、TTL）属于部署配置，不属于图的形状
     */
    public static <E extends Enum<E> & ExpertRouting> CompiledGraph<MultiAgentState> create(
            Class<E> routingEnum,
            String assistantId,
            SupervisorAgent supervisorAgent,
            ExpertAgent expertAgent,
            McpToolCatalog toolCatalog,
            McpClientRegister mcpRegister,
            MemoryRecallService recallService,
            UserMemoryRecorder memoryRecorder,
            BaseCheckpointSaver saver,
            ToolRisk approvalMinRisk) throws GraphStateException {

        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                // ---- 固定部分：四个单例节点 ----
                // recallMemory / writeMemory / toolExecute 都是【总是建】的。
                // 刻意不加"要不要记忆/要不要工具"的开关：一旦开始加开关，
                // 就走上"配置项膨胀 → 最后需要逃生舱"那条路了。
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(supervisorAgent)))
                .addNode(GraphNodes.TOOL_EXECUTE,
                        node(new ToolExecuteNode(mcpRegister, toolCatalog, approvalMinRisk)))
                .addNode(GraphNodes.WRITE_MEMORY, node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, GraphNodes.RECALL_MEMORY)
                .addEdge(GraphNodes.RECALL_MEMORY, GraphNodes.SUPERVISOR)
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);

        // ---- 由枚举驱动的部分：专家节点 + 两张路由表 ----
        Map<String, String> supervisorRoutes = new HashMap<>();
        Map<String, String> toolRoutes = new HashMap<>();
        int expertCount = 0;

        for (E routing : routingEnum.getEnumConstants()) {
            String key = routing.edgeKey();
            String role = routing.expertRole();

            if (role == null) {
                // 不是专家 —— 是"结束"标记。不建节点，只给 supervisor 建一条"指向收尾"的边。
                // 这样就不用在工厂里硬编码 "finish" 这个字符串了。
                supervisorRoutes.put(key, GraphNodes.WRITE_MEMORY);
                continue;
            }

            graph.addNode(key, node(new ExpertNode(role, key, expertAgent, toolCatalog)));
            graph.addEdge(key, GraphNodes.TOOL_EXECUTE);
            // 同一个 key 出现在两张表里：supervisor 派发给它、循环结束时回到它
            supervisorRoutes.put(key, key);
            toolRoutes.put(key, key);
            expertCount++;
        }

        if (expertCount == 0) {
            // 一个专家都没有的图是配置错误，早失败好过跑起来才发现"没人接活"
            throw new IllegalArgumentException(
                    "路由枚举 " + routingEnum.getSimpleName() + " 里没有任何专家（expertRole() 全为 null）");
        }

        // 循环结束 → 回调度器
        toolRoutes.put(LOOP_END, GraphNodes.SUPERVISOR);

        log.info("建图完成: assistantId={} 专家数={} 审批阈值={}", assistantId, expertCount, approvalMinRisk);

        return graph
                .addConditionalEdges(GraphNodes.SUPERVISOR,
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        supervisorRoutes)
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE,
                        AsyncEdgeAction.edge_async(MultiAgentGraphFactory::routeAfterTool),
                        toolRoutes)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .recursionLimit(25)
                        .build());
    }

    /**
     * 工具执行完之后该去哪。
     *
     * <p><b>这是扁平化 ReAct 的心脏</b>：它把"循环"从 Java 的 for 语句挪到了图上的一条条件边 ——
     * 于是每一步都是真实的图节点，<b>既能被 checkpoint，也能被 {@code InterruptibleAction} 拦住</b>。
     * （用 for 循环写不行：{@code interrupt()} 是节点级的，节点内部的循环它看不见。）
     *
     * @return {@code LOOP_END} 表示结束循环回调度器；否则返回专家名（= 回那个专家的 propose 节点）
     */
    private static String routeAfterTool(MultiAgentState state) {
        String tool = state.proposedTool();

        // 模型说"不需要再调工具了" —— 本轮 ReAct 结束
        if (tool == null || tool.isBlank() || NO_TOOL.equals(tool)) {
            return LOOP_END;
        }

        String by = state.proposedBy();
        if (by == null || by.isBlank()) {
            // 边界：提议非空但来源缺失（正常流程不会出现）。
            // 不能瞎猜一个专家 —— 回调度器是唯一安全的选择：
            // 最坏是"少循环一轮、由 supervisor 重新派发"，而不是"跑到一个错的专家那里执行"
            log.warn("proposedTool={} 非空但 proposedBy 为空，无法确定回哪个专家，改为回调度器", tool);
            return LOOP_END;
        }
        return by;
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action) {
        // 这一行是"可中断性"能生效的关键：
        // node_async 内部会 instanceof 检查 InterruptibleAction，
        // 是的话用动态代理再造一个同时实现两个接口的对象。
        // ToolExecuteNode 同时实现了这两个接口 → 走的就是这条路径。
        return AsyncNodeAction.node_async(action);
    }
}
```

---

## 4. 改造 `graph/GraphNodes.java`（它的职责变了）

现在 `GraphNodes` 里放的是"固定节点名"。加了工厂之后，
**专家节点名由枚举提供**，`GraphNodes` 就只管那四个单例节点了。加一行注释说明：

```java
/**
 * 图里固定节点的名字。
 *
 * <p><b>这里只放单例节点。</b> 专家节点的名字来自路由枚举
 * （{@code ExpertRouting.edgeKey()}）—— 它们的数量可变，且有自己唯一的来源，
 * 不重复放在这里。
 *
 * <p><b>为什么仍然值得单独一个类。</b> 这些名字有两类使用方，而且必须一致：
 * 建图时 {@code addNode(name, ...)}，以及 Controller 判断"这次是不是停在中断点上等人批准"。
 * 原来这两处各写一遍字符串，改名时必漏一处，<b>而漏了的症状是"审批静默失效"</b>
 * —— Controller 判不出"停在闸门上"，就把等待中的请求当"已完成"返回给前端。
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

---

## 5. 改造 `agent/SupervisorAgent.java`（枚举实现接口 + 加角色名）

```java
package com.best.multiagent.agent;

import com.best.multiagent.graph.ExpertRouting;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    /**
     * 路由目标。
     *
     * <p><b>划分依据是"属于哪个服务"</b>，而不是"有没有副作用"：
     * 每个专家的工具由<b>网关分发的 token</b> 决定，而 token 是按服务授权的。
     * 按"有没有副作用"分会出现死局 —— 判定为查询 → 交给某个专家 →
     * 但那个专家的 token 根本没有这个服务的工具，他手里没工具，只能编答案。
     *
     * <p><b>{@code @Description} 写的话术直接决定路由准确率</b>
     * （LangChain4j 的 {@code EnumOutputParser.formatInstructions()} 会把它拼进提示词），
     * 所以写得越具体越好。
     *
     * <p><b>常量名要对应 {@code ai.mcp.servers} 的 key</b> ——
     * 这个"三条线合成一条"的约定由 {@link ExpertRouting#edgeKey()} 表达。
     */
    enum Routing implements ExpertRouting {
        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER("订单助手"),

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS("数据分析助手"),

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE("知识库助手"),

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH(null);          // ★ null = "不是专家，只是路由终点"

        private final String role;

        Routing(String role) {
            this.role = role;
        }

        /**
         * 边 key、节点名、MCP server key —— 三者同一个值。
         *
         * <p>统一规则 {@code name().toLowerCase()}：只要改了枚举名，三处一起变，
         * <b>不可能再对不上</b>。
         */
        @Override
        public String edgeKey() {
            return name().toLowerCase();
        }

        @Override
        public String expertRole() {
            return role;
        }
    }

    @SystemMessage("""
            你是任务调度器。根据对话内容，判断下一步该交给谁。

            判断依据：
            - 按【请求涉及的领域】选，不要按"是查询还是修改"选
            - 如果最近的对话里已经有明确的结论或回答，就选 FINISH
            """)
    Routing route(@UserMessage String conversation);
}
```

---

## 6. 改造 `config/MultiAgentGraphConfig.java`（瘦身）

```java
package com.best.multiagent.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.graph.MultiAgentGraphFactory;
import com.best.multiagent.graph.NamespacedKeyNamingStrategy;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    /**
     * 只有 {@code .chatModel(...)}，没有 {@code .tools(...)}。
     *
     * <p>工具由 {@code ToolExecuteNode} 执行 —— 挂了 {@code .tools(...)} 之后
     * LangChain4j 会在专家节点<b>内部</b>当场执行工具，而审批是跨 HTTP 请求的异步动作，
     * 工具已经跑了才问"要不要批准"是没有意义的。
     *
     * <p>⚠️ <b>也不要注入 {@code McpToolProvider}</b>：它是 ai-common 里的现成 Bean，
     * 但 {@code assistant-demo} 在用（那边的普通助手需要"模型自动执行工具"）。
     * 这里用了它，工具就会在专家节点内部被执行，审批就失去意义了。
     */
    @Bean
    public ExpertAgent expertAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(ExpertAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            ExpertAgent expertAgent,
            MemoryRecallService recallService,
            UserMemoryRecorder memoryRecorder,
            McpToolCatalog toolCatalog,
            McpClientRegister mcpClientRegister,
            @Value("${ai.multiagent.assistant-id:${spring.application.name:multiagent}}")
            String assistantId,
            @Value("${ai.multiagent.approval-min-risk:high}") String approvalMinRiskRaw,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${ai.multiagent.checkpoint-ttl-hours:24}") long ttlHours
    ) throws GraphStateException {

        // 用 ToolRisk.parse 解析配置值 —— 它"未知值一律按 HIGH"的语义正好也用在这里：
        // 配置里写成 "critical" 这种错值时，结果是【更严格】而不是更宽松。
        // 用 valueOf 的话会抛异常让服务起不来，一个配置拼写错误不该是启动失败
        ToolRisk approvalMinRisk = ToolRisk.parse(approvalMinRiskRaw);

        var saver = buildRedisSaver(redisHost, redisPort, redisPassword, ttlHours, assistantId);

        // ★ 一行建图。加一个 expert 只需要改 Routing 枚举。
        return MultiAgentGraphFactory.create(
                SupervisorAgent.Routing.class,
                assistantId,
                supervisorAgent,
                expertAgent,
                toolCatalog,
                mcpClientRegister,
                recallService,
                memoryRecorder,
                saver,
                approvalMinRisk);
    }

    /**
     * checkpoint saver 的构造。
     *
     * <p>它留在 Config 而不是工厂里：Redis 地址、密码、TTL 都是<b>部署配置</b>，
     * 不是"图的形状"。工厂只接受一个建好的 saver。
     */
    private RedisSaver buildRedisSaver(String host, int port, String password,
                                       long ttlHours, String namespace) {
        var builder = RedisSaver.builder()
                .host(host)
                .port(port)
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
                // 加服务命名空间 —— 不加的话多个图应用共用 Redis 会串号（静默的）
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(namespace))
                .ttl(ttlHours, TimeUnit.HOURS);
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        var saver = builder.build();
        log.info("Checkpoint 已启用: RedisSaver {}:{} ttl={}h namespace={}", host, port, ttlHours, namespace);
        return saver;
    }
}
```

**对比一下**：改造前这个方法体约 **60 行**，现在 **8 行**（含注释）。而且加 expert 再也不用碰它。

---

## 7. 验收

### 7.1 这是重构 —— 验收标准是"行为不变"

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

启动日志里应该多一行：

```
建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH
```

**然后重跑 32 号第 4 节那四条用例**，结果必须和改造前一模一样：

| 用例 | 期望 |
|---|---|
| 低危工具 | `{"status":"done"}`，日志有"直接执行" |
| 高危工具 | `{"status":"waiting_approval"}`，工具**没执行** |
| 批准 | 工具执行**一次** |
| 连续两次高危 | 第二次仍然 `waiting_approval` |
| **32 号的硬指标** | `调度决定` **≤ 2 次** |

### 7.2 验证本单的收益：加一个 expert 试试

这是最有说服力的验收 —— **只改两处**：

**① `SupervisorAgent.Routing` 加一行**：

```java
        @Description("物流相关：查运单、查配送进度、查签收情况")
        LOGISTICS("物流助手"),
```

**② `application.yml` 的 `ai.mcp.servers` 加一个**（token 你去网关建）：

```yaml
      logistics:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_LOGISTICS}"
        timeout: 30s
```

**然后重启 —— 什么都不用改。** 启动日志会变成 `专家数=4`，
问一句物流相关的问题，应该路由到新的专家。

> 验完把这个枚举值和 yml 段删掉即可（或者留着，等 token 建好）。
>
> **这个"只改两处"的体感，就是本单的全部价值。**

---

## 8. 自查清单

**编译期**
- [ ] `MultiAgentGraphConfig` 里不再有 `addNode` / `addConditionalEdges`（全在工厂里）
- [ ] `routeAfterTool` 和 `LOOP_END` 已经从 Config 移到了工厂类（不能两处都有）
- [ ] `ExpertRouting` 的两个方法在 `Routing` 里都实现了
- [ ] `FINISH(null)` 那个构造参数没漏 —— 漏了会编译不过（枚举构造签名不一致）

**行为**
- [ ] 启动日志 `专家数=3`
- [ ] 32 号的硬指标（`调度决定 ≤ 2`）仍然成立
- [ ] 审批、拒绝、连续两次高危，四个用例全过

**边界**
- [ ] 故意把 `ORDER` 的 `expertRole()` 改成 `null` → 启动应该抛
      `IllegalArgumentException: ... 里没有任何专家`（如果三个都改）
- [ ] 新增一个 expert（7.2 节）→ 只改两处就能用

---

## 9. 这份工厂**不做**什么（写下来避免以后重提）

| 不做 | 理由 |
|---|---|
| **不加"要不要记忆/要不要工具"的开关** | 一旦开始加开关，就走上"配置项膨胀 → 最后需要逃生舱"的路。**"总是建"是刻意选择** |
| **不泛型化 `SupervisorAgent.route()` 的返回类型** | 那要等"真的要做第二个图应用"时才有意义。现在只有一个图，直接沿用 `SupervisorAgent.Routing` 最简单 |
| **不移出 `ai-multiagent`** | 移出去就变成"有依赖成本的框架"了。**它现在是一个模块内部的建图工具** |
| **不抽 `ai-graph` 模块** | 5 个类的使用者仍然只有 1 个。触发条件不变：**出现第二个 langgraph4j 图应用时** |
