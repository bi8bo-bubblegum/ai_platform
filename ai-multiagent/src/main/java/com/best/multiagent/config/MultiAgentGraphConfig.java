package com.best.multiagent.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.graph.GraphNodes;
import com.best.multiagent.graph.NamespacedKeyNamingStrategy;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    private static final String AGENT_ORDER = SupervisorAgent.Routing.ORDER.edgeKey();
    private static final String AGENT_ANALYSIS = SupervisorAgent.Routing.ANALYSIS.edgeKey();
    private static final String AGENT_KNOWLEDGE = SupervisorAgent.Routing.KNOWLEDGE.edgeKey();
    private static final String AGENT_FINISH = SupervisorAgent.Routing.FINISH.edgeKey();

    private static final String LOOP_END = "loopEnd";

    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister){
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    public ExpertAgent expertAgent(ChatModelRegister chatModelRegister){
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
    ) throws GraphStateException{
        ToolRisk approvalMinRisk = ToolRisk.parse(approvalMinRiskRaw);
        log.info("工具审批阈值: {}", approvalMinRisk);
        var saverBuilder = RedisSaver.builder()
                .host(redisHost)
                .port(redisPort)
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(assistantId))
                .ttl(ttlHours, TimeUnit.HOURS);
        if(redisPassword != null && !redisPassword.isBlank()){
            saverBuilder.password(redisPassword);
        }
        var saver = saverBuilder.build();
        log.info("Checkpoint 已启用: RedisSaver {}:{} ttl={}h", redisHost, redisPort, ttlHours);
        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(supervisorAgent)))
                .addNode(AGENT_ORDER, node(new ExpertNode("订单助手", AGENT_ORDER, expertAgent, toolCatalog)))
                .addNode(AGENT_ANALYSIS, node(new ExpertNode("数据分析助手", AGENT_ANALYSIS, expertAgent, toolCatalog)))
                .addNode(AGENT_KNOWLEDGE, node(new ExpertNode("知识库助手", AGENT_KNOWLEDGE, expertAgent, toolCatalog)))
                .addNode(GraphNodes.TOOL_EXECUTE, node(new ToolExecuteNode(mcpClientRegister, toolCatalog, approvalMinRisk)))
                .addNode(GraphNodes.WRITE_MEMORY, node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, GraphNodes.RECALL_MEMORY)
                .addEdge(GraphNodes.RECALL_MEMORY, GraphNodes.SUPERVISOR)
                .addConditionalEdges(GraphNodes.SUPERVISOR,
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        Map.of(
                                AGENT_ORDER, AGENT_ORDER,
                                AGENT_ANALYSIS, AGENT_ANALYSIS,
                                AGENT_KNOWLEDGE, AGENT_KNOWLEDGE,
                                AGENT_FINISH, GraphNodes.WRITE_MEMORY
                        ))
                .addEdge(AGENT_ORDER, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_ANALYSIS, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_KNOWLEDGE, GraphNodes.TOOL_EXECUTE)
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE,
                        AsyncEdgeAction.edge_async(MultiAgentGraphConfig::routeAfterTool),
                        Map.of(
                                AGENT_ORDER, AGENT_ORDER,
                                AGENT_ANALYSIS, AGENT_ANALYSIS,
                                AGENT_KNOWLEDGE, AGENT_KNOWLEDGE,
                                LOOP_END, GraphNodes.SUPERVISOR
                        ))
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(25)
                .build());
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action){
        return AsyncNodeAction.node_async(action);
    }

    private static String routeAfterTool(MultiAgentState state){
        String tool = state.proposedTool();
        if(tool == null || tool.isBlank() || "NONE".equals(tool)){
            return LOOP_END;
        }
        String by = state.proposedBy();
        if(by == null || by.isBlank()){
            log.warn("proposedTool={} 非空但 proposedBy 为空，无法确定回哪个专家，改为回调度器", tool);
            return LOOP_END;
        }
        return by;
    }
}
