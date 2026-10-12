# 36 · multiagent 建图最终版：`createAgent(枚举, 调度器)`

> **本文档取代 `34-建图工厂` 与 `35-新增图应用-接入清单`**（那两份保留作历史，含完整的推导过程）。
>
> **这是最终形态**：建一张完整的图只需要 **2 个参数**，其余 8 项依赖全部默认注入。

---

## 0. 最终长什么样

**调用方（`MultiAgentGraphConfig`）的全部内容：**

```java
@Bean
public CompiledGraph<MultiAgentState> multiAgentGraph(
        SupervisorAgent supervisorAgent,
        MultiAgentGraphFactory graphFactory) throws GraphStateException {
    return graphFactory.createAgent(SupervisorAgent.Routing.class, supervisorAgent);
}
```

**两行。** 原来这里是 60 行手工建图。

**加一个 expert 只改 2 处**：`Routing` 枚举加一行 + yml 加一个 token。

---

## 1. 参数是怎么减掉的

| 原来要传 | 现在 | 为什么能默认 |
|---|---|---|
| `Class<E> routingEnum` | ✅ **保留** | 每个图的业务划分，只能由调用方给 |
| `SupervisorAgent` | ✅ **保留** | 同上 —— 它的枚举就是这个图的业务契约 |
| `ExpertAgent` | 默认 | 全平台一个实现（`.chatModel(...)`，**不挂 tools**），工厂自己建 |
| `McpToolCatalog` | 默认 | 注入 |
| `McpClientRegister` | 默认 | 注入 |
| `MemoryRecallService` | 默认 | 注入 |
| `UserMemoryRecorder` | 默认 | 注入 |
| `BaseCheckpointSaver saver` | 默认 | 工厂自己建（读 `spring.data.redis.*`） |
| `String assistantId` | 默认 | 读 `ai.multiagent.assistant-id`，缺省取 `spring.application.name` |
| `ToolRisk approvalMinRisk` | 默认 | 读 `ai.multiagent.approval-min-risk`，缺省 `HIGH` |

**调用方从 10 个参数变成 2 个。**

---

## 2. 代码

### 2.1 `graph/ExpertRouting.java`（新增）

```java
package com.best.multiagent.graph;

/**
 * 「路由枚举」需要提供的信息。
 *
 * <p>建图工厂要 for 循环遍历枚举、为每个值建一个专家节点，它必须能回答两个问题：
 * <b>这个值叫什么名字</b>、<b>它是不是一个专家</b>。
 *
 * <p>用接口而不是反射读注解，是因为这两件事都<b>必须在编译期就能表达</b>。
 */
public interface ExpertRouting {

    /**
     * 边上的 key。<b>它同时是三样东西</b>：
     * 专家节点的名字、supervisor 条件边的 key、`ai.mcp.servers` 里的 key。
     *
     * <p>三者同一个值是刻意的 —— 它们本来就该一致
     * （"这个专家" = "这条路由" = "这个 token 能看到的工具"），分开配置只会漂移。
     */
    String edgeKey();

    /**
     * 中文角色名，会写进专家的系统提示词（"你是{{role}}"）。
     *
     * <p><b>返回 {@code null} 表示"这个枚举值不是专家"</b> —— 比如 {@code FINISH}，
     * 它只是 supervisor 的一个路由终点。建图循环会跳过它建节点，
     * 但会给它建一条"指向收尾节点"的边。
     * <b>这样工厂里就不用硬编码 {@code "finish"} 这个字符串了。</b>
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

### 2.2 `config/MultiAgentProperties.java`（新增）

```java
package com.best.multiagent.config;

import com.best.ai_common.mcp.ToolRisk;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体图的配置项。
 *
 * <p><b>为什么用一个属性类而不是散落的 {@code @Value}。</b>
 * 这三个值有明确的默认值语义，写成属性类之后：
 * ① 默认值写在字段上（少一个"服务没配就崩"的可能）；
 * ② 一眼能看全"这个图有哪些可调项"；
 * ③ 从 yml 到对象的路径是显式的，不依赖字符串 key。
 */
@Data
@ConfigurationProperties(prefix = "ai.multiagent")
public class MultiAgentProperties {

    /**
     * 这个图的标识。
     *
     * <p><b>它是双重标识，重复会静默串号</b>：
     * ① checkpoint 的 key 命名空间；② 长期记忆表的 {@code source_assistant}。
     *
     * <p>留空则取 {@code spring.application.name} —— 所以新建服务时
     * <b>只要服务名唯一，这项就可以不配</b>。
     */
    private String assistantId;

    /**
     * 达到或超过这个等级的工具，需要人工批准后才执行。
     *
     * <p><b>刻意声明成 String 而不是 {@code ToolRisk}。</b>
     * 用枚举类型的话，配置绑定会走 {@code valueOf} —— 写错一个字母就抛异常、服务起不来。
     * 而这里最需要的行为是 {@link ToolRisk#parse} 的：<b>不认识的值按 HIGH 处理</b>
     * （即"更严格"，不是"更宽松"）。
     */
    private String approvalMinRisk = "high";

    /** checkpoint 的存活时间（小时）。会话状态不是资产，配 TTL 避免无限堆积 */
    private long checkpointTtlHours = 24;

    /** 解析成枚举。任何不认识的值都会变成 HIGH 并打一条 warn */
    public ToolRisk approvalMinRisk() {
        return ToolRisk.parse(approvalMinRisk);
    }
}
```

### 2.3 `graph/MultiAgentGraphFactory.java`（新增）★ 核心

```java
package com.best.multiagent.graph;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.config.MultiAgentProperties;
import com.best.multiagent.node.*;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 建图工厂 —— <b>两个参数建出一整张多智能体图</b>。
 *
 * <p>它建的图<b>形状是固定的</b>：
 * <pre>
 *   START → recallMemory → supervisor ⇄ [propose 专家 × N] ⇄ toolExecute
 *                              │
 *                              └──(结束)──→ writeMemory → END
 * </pre>
 *
 * <p><b>形状固定不是缺陷，是这个工厂的定义。</b> 它住在 {@code ai-multiagent} 里，
 * 哪天这个应用需要别的形状，直接改这里 —— 影响范围就这一个模块。
 * 它变成"有代价的框架"，是从<b>被移出这个模块</b>那一刻开始的。
 *
 * <p><b>它变的是"有几个专家"</b>：由传入的路由枚举决定。加一个 expert 只需在枚举里加一行 ——
 * 节点、边、路由表全部自动生成，<b>不存在"漏改一处"的可能</b>
 * （而手工建图时，"忘了加 addNode"和"忘了加路由表"都是<b>静默失效</b>的）。
 */
@Slf4j
@Component
public class MultiAgentGraphFactory {

    /**
     * 工具执行完之后"结束循环、回调度器"的边 key。
     *
     * <p>刻意不复用 {@code Routing.FINISH.edgeKey()}：{@code FINISH} 的含义是
     * "整个任务结束"，这里是"本轮 ReAct 结束、回去看全局" ——
     * 两件事借用同一个名字是将来最容易读错的地方。
     */
    private static final String LOOP_END = "loopEnd";

    /** 没有提议时专家写的标记。和 {@code ExpertNode}/{@code ToolExecuteNode} 里的约定一致 */
    private static final String NO_TOOL = "NONE";

    private final ChatModelRegister chatModelRegister;
    private final McpToolCatalog toolCatalog;
    private final McpClientRegister mcpRegister;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder memoryRecorder;
    private final RedisProperties redisProperties;
    private final MultiAgentProperties properties;
    private final String applicationName;

    public MultiAgentGraphFactory(
            ChatModelRegister chatModelRegister,
            McpToolCatalog toolCatalog,
            McpClientRegister mcpRegister,
            MemoryRecallService recallService,
            UserMemoryRecorder memoryRecorder,
            RedisProperties redisProperties,
            MultiAgentProperties properties,
            @Value("${spring.application.name:multiagent}") String applicationName) {
        this.chatModelRegister = chatModelRegister;
        this.toolCatalog = toolCatalog;
        this.mcpRegister = mcpRegister;
        this.recallService = recallService;
        this.memoryRecorder = memoryRecorder;
        this.redisProperties = redisProperties;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    /**
     * 建一张多智能体图。
     *
     * <p><b>全部只需要两个参数</b>：路由枚举 + 调度器。其余依赖都是这个平台上的单例 Bean，
     * 由工厂自己注入；saver 由工厂按 {@code spring.data.redis.*} 自建 ——
     * 因为 Redis 地址属于部署配置，不属于"图的形状"。
     *
     * @param routingEnum    路由枚举。<b>每个值要么是一个专家，要么是"结束"标记</b>
     *                       （靠 {@link ExpertRouting#expertRole()} 是否为 null 区分）
     * @param supervisorAgent 这个图的调度器。<b>它的嵌套枚举就是 routingEnum</b> ——
     *                        两者绑定的原因是：调度器的职责就是"在这些枚举值之间选择"，
     *                        拆成两个东西会漂移
     */
    public <E extends Enum<E> & ExpertRouting> CompiledGraph<MultiAgentState> createAgent(
            Class<E> routingEnum,
            SupervisorAgent supervisorAgent) throws GraphStateException {

        // 专家 Agent 全平台一个实现：只有 .chatModel(...)，【没有 .tools(...)】。
        // 工具由 ToolExecuteNode 执行 —— 挂了 .tools(...) 之后 LangChain4j 会在专家节点
        // 内部当场执行工具，而审批是跨 HTTP 请求的异步动作，
        // 工具已经跑了才问"要不要批准"是没有意义的。
        ExpertAgent expertAgent = AiServices.builder(ExpertAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();

        String assistantId = resolveAssistantId();
        RedisSaver saver = buildRedisSaver(assistantId);

        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                // ---- 固定部分：四个单例节点，【总是建】 ----
                // 刻意不加"要不要记忆 / 要不要工具"的开关：一旦开始加开关，
                // 就走上"配置项膨胀 → 每个组合都有人要 → 最后需要逃生舱"那条路了。
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(supervisorAgent)))
                .addNode(GraphNodes.TOOL_EXECUTE,
                        node(new ToolExecuteNode(mcpRegister, toolCatalog, properties.approvalMinRisk())))
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
            // 一个专家都没有的图是配置错误 —— 早失败好过跑起来才发现"没人接活"
            throw new IllegalArgumentException("路由枚举 " + routingEnum.getSimpleName()
                    + " 里没有任何专家（expertRole() 全为 null）");
        }

        // 循环结束 → 回调度器
        toolRoutes.put(LOOP_END, GraphNodes.SUPERVISOR);

        log.info("建图完成: assistantId={} 专家数={} 审批阈值={}",
                assistantId, expertCount, properties.approvalMinRisk());

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
     *
     * <p>（用 Java for 循环写不行：{@code interrupt()} 是<b>节点级</b>的，
     * 节点内部的循环它看不见 —— 审批最多只能拦住第一次。）
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

    /** 没配 {@code ai.multiagent.assistant-id} 就用服务名 —— 只要服务名唯一，这一项就可以不配 */
    private String resolveAssistantId() {
        String configured = properties.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }

    private RedisSaver buildRedisSaver(String namespace) {
        var builder = RedisSaver.builder()
                .host(redisProperties.getHost())
                .port(redisProperties.getPort())
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
                // 加服务命名空间 —— 不加的话多个图应用共用 Redis 会串号（静默的）
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(namespace))
                .ttl(properties.getCheckpointTtlHours(), TimeUnit.HOURS);
        String password = redisProperties.getPassword();
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return builder.build();
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action) {
        // ★ 这一行是"可中断性"能生效的关键：
        // node_async 内部会 instanceof 检查 InterruptibleAction，
        // 是的话用动态代理再造一个同时实现两个接口的对象。
        // ToolExecuteNode 同时实现了这两个接口 → 走的就是这条路径。
        return AsyncNodeAction.node_async(action);
    }
}
```

> **注意 `import com.best.ai_common.register.ChatModelRegister;`** —— 上面为了短没重复写，
> 实际要加。

### 2.4 `agent/SupervisorAgent.java`（改造：枚举实现接口 + 加角色名）

```java
package com.best.multiagent.agent;

import com.best.multiagent.graph.ExpertRouting;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    /**
     * 路由目标。<b>这个枚举就是"这张图有哪些专家"的唯一声明</b> ——
     * 节点、边、路由表全部由它生成。
     *
     * <p><b>划分依据是"属于哪个服务"</b>，而不是"有没有副作用"：
     * 每个专家的工具由<b>网关分发的 token</b> 决定，而 token 是按服务授权的。
     * 按"有没有副作用"分会出现死局 —— 判定为查询 → 交给某个专家 →
     * 但那个专家的 token 根本没有这个服务的工具，他手里没工具，只能编答案。
     *
     * <p><b>{@code @Description} 写的话术直接决定路由准确率</b>
     * （LangChain4j 的 {@code EnumOutputParser.formatInstructions()} 会把它拼进提示词），
     * 所以写得越具体越好。
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

### 2.5 `config/MultiAgentGraphConfig.java`（改造：只剩两个 Bean）

```java
package com.best.multiagent.config;

import com.best.ai_common.register.ChatModelRegister;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.graph.MultiAgentGraphFactory;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.service.AiServices;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多智能体图的装配。
 *
 * <p><b>整个类只有两个 Bean</b> —— 建图的全部复杂性都在 {@link MultiAgentGraphFactory} 里。
 * 想改图的形状就去改工厂；这里只负责"把业务契约和工厂接起来"。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MultiAgentProperties.class)
public class MultiAgentGraphConfig {

    /** 这个图的调度器。它的 {@code Routing} 枚举就是这张图的业务划分 */
    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    /** ★ 一行建图 */
    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            MultiAgentGraphFactory graphFactory) throws GraphStateException {
        return graphFactory.createAgent(SupervisorAgent.Routing.class, supervisorAgent);
    }
}
```

### 2.6 `graph/GraphNodes.java`（保持不变，补注释）

```java
package com.best.multiagent.graph;

/**
 * 图里固定节点的名字。
 *
 * <p><b>这里只放单例节点。</b> 专家节点的名字来自路由枚举
 * （{@link ExpertRouting#edgeKey()}）—— 它们的数量可变，且有自己唯一的来源。
 *
 * <p><b>为什么仍然值得单独一个类。</b> 这些名字有两类使用方，而且必须一致：
 * 建图时 {@code addNode(name, ...)}，以及 Controller 判断"这次是不是停在中断点上等人批准"。
 * 原来这两处各写一遍字符串，改名时必漏一处 ——
 * <b>而漏了的症状是"审批静默失效"</b>：Controller 判不出"停在闸门上"，
 * 就把等待中的请求当"已完成"返回给前端。
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

### 2.7 `application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: ai-multiagent          # ← 会自动成为 assistantId，所以服务名要唯一
  datasource:
    url: jdbc:mysql://localhost:3306/mcp_gateway?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
  data:
    redis:
      host: localhost
      port: 6379

ai:
  multiagent:
    # assistant-id 可以不配 —— 缺省取 spring.application.name。
    # ★ 但它是【双重标识】（checkpoint 命名空间 + 长期记忆 source_assistant），
    #   重复会静默串号，所以服务名一定要唯一。
    # approval-min-risk 也可以不配，默认 high
    checkpoint-ttl-hours: 24

  mcp:
    enabled: true
    tool-timeout: 60s
    risk-cache-ttl: 60s
    servers:
      # ⚠️ token 在网关后台建，每个 expert 一个，权限按服务隔离
      order:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_ORDER}"
        timeout: 30s
      analysis:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_ANALYSIS}"
        timeout: 30s
      knowledge:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_KNOWLEDGE}"
        timeout: 30s

  chat:
    profile: free
    temperature: 0.7
    max-tokens: 2000
    timeout: 60s
    endpoints:
      free:
        base-url: ${GATEWAY_BASE_URL}
        api-key: ${GATEWAY_API_KEY}
        model-name: best-1-nothinking

  memory:
    session:
      store: redis
      max-messages: 20
      summary-enabled: true
      summary-ttl: 96h
      ttl: 48h
    user:
      enabled: true
      top-k: 5
      min-score: 0.30
      extract-profile: free
      dedup-threshold: 0.95
      max-facts-per-user: 500

  embed:
    profile: default
    dimensions: 1536
    timeout: 5s
    max-retries: 3
    endpoints:
      default:
        base-url: ${CLOSEAI_BASE_URL}
        api-key: ${CLOSEAI_API_KEY}
        model-name: text-embedding-3-small
```

---

## 3. 加一个 expert：只改 2 处

**① `SupervisorAgent.Routing` 加一行：**

```java
        @Description("物流相关：查运单、查配送进度、查签收情况")
        LOGISTICS("物流助手"),
```

**② `application.yml` 的 `ai.mcp.servers` 加一段**（token 在网关后台建，只授权物流服务）。

**重启，完事。** 启动日志从 `专家数=3` 变成 `专家数=4`。

**不再需要改的地方**（原来要手工改、而且漏了不报错）：
- ~~`.addNode(...)`~~ → 自动生成
- ~~条件边 `Map.of(...)`~~ → 自动生成

---

## 4. 加一个图应用：复制模块

**同一个服务里跑两个图现在做不到** —— `SupervisorAgent` 的枚举是每个图自己的，
而泛型化 `interface SupervisorAgent<R extends Enum<R>>` 会让 LangChain4j 失效
（`AiServices.builder()` 传 `Class`，擦除后 `route()` 返回裸 `Enum`，
`EnumOutputParser` 拿不到"有哪几个值"，提示词里那句
`You must answer strictly with one of these enums:` 就生成不出来）。

**所以：一个服务 = 一张图。加图 = 加服务。**

```bash
cd /Users/best/project/ai_platform
cp -r ai-multiagent ai-docgraph
cd ai-docgraph && rm -rf target
# 全局替换包名：com.best.multiagent → com.best.docgraph
```

### 要改的

| # | 位置 | 改什么 |
|---|---|---|
| 1 | `pom.xml` | `artifactId` |
| 2 | 父 `pom.xml` | `<modules>` 加一行 |
| 3 | 包名 | `com.best.multiagent` → 你的 |
| 4 | `MultiAgentApplication` | 类名 |
| 5 | **`SupervisorAgent` 的枚举 + `@SystemMessage`** | ★ **这是这个图的核心业务** |
| 6 | `MultiAgentState` | 类名；**按需**加字段（多数图不用加） |
| 7 | `application.yml` | 见下 |
| 8 | `AgentController` | `@RequestMapping` 路径 |
| 9 | `MultiAgentProperties` | 包名（内容不用改） |

**`application.yml` 必须唯一的四项：**

| 配置 | 重复的后果 |
|---|---|
| `server.port` | 起不来 —— 立刻发现 ✓ |
| `spring.application.name` | 看不出问题 |
| **`ai.multiagent.assistant-id`** | ★ **静默串号**：checkpoint 串了可清 Redis 恢复，长期记忆的 `source_assistant` 串了**不可逆** |
| `ai.mcp.servers.*` | 该专家无工具（有 warn） |

### **不用改的**（这份清单比"要改的"更有价值）

```
ExpertRouting · MultiAgentGraphFactory · MultiAgentProperties · GraphNodes
SupervisorNode · ExpertNode · ToolExecuteNode
RecallMemoryNode · WriteMemoryNode
GraphThreadIds · NamespacedKeyNamingStrategy
ai-common · ai-memory                      ← 一行都不用动
```

**新图的代码工作只剩三件事：写枚举、写提示词、填 yml。**

---

## 5. 陷阱清单（13 条，按症状组织）

### 编译期能抓到的

| 症状 | 根因 |
|---|---|
| 写 `InterruptableAction` 找不到方法 | ★ **真接口是 `InterruptibleAction`（带 i）**；不带 i 的是**空壳标记接口** |
| 节点写好了但中断不生效 | 忘了 `AsyncNodeAction.node_async(...)` 包一层 —— **只有它内部会检测 `InterruptibleAction` 并动态代理** |
| 枚举加了值但编译报错 | 穷尽 `switch` 没覆盖（**这是好事**，接线点被编译器守住了） |

### ★ 编译期抓不到、静默失效的

| 症状 | 根因 | 怎么防 |
|---|---|---|
| **审批拦不住第二次危险操作** | `approvalDecided` 没被重置 → `interrupt()` 看到 `true` 直接放行 | **所有 return 路径都要过 `finishXxx()`**；两处重置（`finish()` + `/chat` 初始化） |
| **用户点"拒绝"，工具照样执行** | `apply()` 少了拒绝分支 | 判据必须是 `approvalDecided && !approved`（两个条件的**与**） |
| **ReAct 循环转不起来** | `finish()` 无条件清空 `proposedTool`，而条件边要靠它判断 | 拆成 `finishContinue`（保留）/ `finishDone`（写 `NONE`） |
| **长期记忆永远写不进去** | memoryId 手拼**少一个 `::`** → `SessionKey.parse` 抛异常被 catch 成 warn | **用 `SessionKey.of(...).toMemoryId()`，不手拼** |
| **supervisor 判 FINISH 时路由失败** | `edgeKey()` 返回小写 `"finish"`，Map 的 key 是 `"FINISH"` | **Map 的 key 也用 `Routing.FINISH.edgeKey()`** |
| **Controller 判不出"停在中断点"** | 节点名两处各写一遍，改名漏一处 | **节点名走 `GraphNodes` 单一来源** |
| **记忆偶尔不生效** | 把 `state.messages()`（活引用）传给异步线程 → 并发修改异常只打在异步线程日志里 | 传 `List.copyOf(...)` |
| **日志里工具结果占十几行** | `replace` 是**按字面量**替换，`replaceAll` 才是正则 | 用 `replaceAll("\\s+", " ")` |

### 架构性的（选错了路会白干）

| 症状 | 根因 |
|---|---|
| **工具在专家节点内部就被执行了，审批形同虚设** | 给 `AiServices` 挂了 `.tools(...)`，或注入了 `McpToolProvider` |
| **子图里的中断，父图完全感知不到** | 子图中断**不会传播**（实测）—— 父图静默返回残缺状态，Controller 看不到"需要审批" |
| **审批停不下来 / 所有操作都要审批** | 还在用 `CompileConfig.interruptBefore(...)` —— 它是**编译期无条件中断** |

---

## 6. 验收

### 6.1 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

启动日志应该有一行：

```
建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH
```

### 6.2 四个用例，一个都不能少

```bash
# ① 低危工具 → 直接执行，不停
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'
# 期待 {"status":"done"}，日志有"未达审批阈值，直接执行"

# ② 高危工具 → 必须停下，且工具【没有】被执行
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'
# 期待 {"status":"waiting_approval","proposedTool":"mysql__mysql_query","proposedArgs":"{...}"}

# ③ 批准 → 工具执行，且【只执行一次】
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
grep -c "工具执行成功" <日志>          # 必须恰好 1

# ④ 连续两次高危 → 第二次照样必须停
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"再统计一遍","sessionId":"r2"}'
# ★ 仍期待 waiting_approval。如果返回 done —— approvalDecided 没重置
```

### 6.3 硬指标

```bash
grep -c "调度决定" <日志>       # ★ 必须 ≤ 2（派发 + 收尾），与工具轮数无关
```

### 6.4 本单特有：加一个 expert 试试

按第 3 节加 `LOGISTICS`（改 2 处）→ 重启 → 日志 `专家数=4` → 问一个物流问题应能路由过去。
**验完删掉即可。**

---

## 7. 这个工厂**不做**什么（写下来避免以后重提）

| 不做 | 理由 |
|---|---|
| 加"要不要记忆 / 要不要工具"的开关 | **"总是建"是刻意选择**。一旦开始加开关，就走上"配置组合爆炸 → 最后需要逃生舱"的路 |
| 泛型化 `SupervisorAgent.route()` 的返回类型 | 会让 LangChain4j 的 `EnumOutputParser` 拿不到枚举值。**每个图必须有自己的 `SupervisorAgent` 接口** |
| 移出 `ai-multiagent` | 移出去就变成"有依赖成本的框架"。**它现在是模块内部的建图工具** |
| 抽 `ai-graph` 公共模块 | 5 个类的使用者仍只有 1 个。**触发条件：出现第二个 langgraph4j 图应用、并且写完验证通过之后** |

**那时才该做的判断**（只有那时能做的）：

> **不是看"哪些类长得一样"，而是看"哪些改动必须两边同时改"。**
> 必须同时改 → 真共享 → 值得抽；长得像但可各自演化 → 假共享 → 留在各自模块。

**从今天的经验猜**：`MultiAgentGraphFactory` / `ExpertRouting` / `MultiAgentProperties` /
`GraphNodes` / `ToolExecuteNode` 值得抽（它们只认机制）；
而**枚举、提示词、state 字段一定不抽**（它们是业务）。

---

## 8. 另一件更确定的事（与抽模块无关）

`RecallMemoryNode` / `WriteMemoryNode` 里那些（召回文本模板、memoryId 构造、失败兜底策略）
应该收进 **`ai-memory`** 的 `UserMemoryFacade`：

```java
// ai-memory 里
String recallAsText(String userId, String query);              // 召回 + 渲染 + 兜底
void writeAsync(String userId, String sessionId, List<ChatMessage> messages);
```

- **和抽 `ai-graph` 无关** —— 它本来就是记忆模块的领域
- `ai-memory` **不该被拖上 langgraph4j**，所以门面必须是图无关的
- 收益：召回文本模板、memoryId 构造、失败兜底策略**各收一处**；
  而且 memoryId 由 `SessionKey` 在内部构造，**调用方连拼错的机会都没有**
- 节点那边留 8 行适配（认识 state 的代码必须住在业务侧）

**这件事可以独立做，不依赖第二个图应用出现。**
