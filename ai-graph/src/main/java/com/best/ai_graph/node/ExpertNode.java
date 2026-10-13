package com.best.ai_graph.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_graph.agent.ExpertAgent;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.state.MultiAgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ExpertNode implements NodeAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExpertRouting routing;
    private final ExpertAgent expertAgent;
    private final McpToolCatalog toolCatalog;
    private final GraphProperties graphProperties;

    public ExpertNode(ExpertRouting routing, ExpertAgent expertAgent, McpToolCatalog toolCatalog, GraphProperties graphProperties) {
        this.routing = routing;
        this.expertAgent = expertAgent;
        this.toolCatalog = toolCatalog;
        this.graphProperties = graphProperties;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String role = routing.expertRole();
        String clientKey = routing.edgeKey();
        List<McpTool> tools = toolCatalog.list(clientKey);
        Map<String, Object> update = new HashMap<>();
        int step = state.stepCount() + 1;
        update.put(MultiAgentState.STEP_COUNT, step);
        update.put(MultiAgentState.EXPERT_ERROR, false);
    }

    private String normalizeTool(String proposed, List<McpTool> available, String request) {
        if (proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())) {
            return NO_TOOL;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if (!exists) {
            log.warn("专家 {} 提议了一个不在清单里的工具: [{}]。可用: {}。请求: {}",
                    routing.expertRole(), trimmed, available.stream().map(McpTool::name).toList(), request);
            return NO_TOOL;
        }
        return trimmed;
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

    private static String safe(String s){
        return s == null ? "" : s;
    }
}
