package com.best.ai_graph.graph;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.agent.ExpertAgent;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.node.*;
import com.best.ai_graph.state.MultiAgentState;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MultiAgentGraphFactory {

    private static final String LOOP_END = "loopEnd";

    private static final String NO_TOOL = "NONE";

    private final ChatModelRegister chatModelRegister;
    private final McpToolCatalog toolCatalog;
    private final McpClientRegister mcpRegister;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder memoryRecorder;
    private final RedisProperties redisProperties;
    private final GraphProperties graphProperties;
    private final String applicationName;

    public MultiAgentGraphFactory(ChatModelRegister chatModelRegister, McpToolCatalog toolCatalog, McpClientRegister mcpRegister, MemoryRecallService recallService, UserMemoryRecorder memoryRecorder, RedisProperties redisProperties, GraphProperties graphProperties, String applicationName) {
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
    ) throws GraphStateException{
        ExpertAgent expertAgent = AiServices.builder(ExpertAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
        String assistantId = resolveAssistantId();
        RedisSaver saver = buildRedisSaver(assistantId);
        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, new LC4jStateSerializer<>(MultiAgentState::new))
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(decider, fallbackEdgeKey)))
                .addNode(GraphNodes.TOOL_EXECUTE, node(new ToolExecuteNode(mcpRegister, toolCatalog, graphProperties.approvalMinRisk())))
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
            graph.addNode(key, node(new ExpertNode(routing, expertAgent, toolCatalog)));
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
        log.info("建图完成: assistantId={} 专家数={} 审批阈值={}",
                assistantId, expertCount, graphProperties.approvalMinRisk());
        return graph
                .addConditionalEdges(GraphNodes.SUPERVISOR, AsyncEdgeAction.edge_async(MultiAgentState::next), supervisorRoutes)
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE, AsyncEdgeAction.edge_async(MultiAgentGraphFactory::routeAfterTool), toolRoutes)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .recursionLimit(25)
                        .releaseThread(false)
                        .build());
    }

    private static String routeAfterTool(MultiAgentState state){
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
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
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
