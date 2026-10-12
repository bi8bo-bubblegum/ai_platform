package com.best.multiagent.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.message.ChatMessageTexts;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.state.MultiAgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ExpertNode implements NodeAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String role;

    private final String clientKey;

    private final ExpertAgent expertAgent;

    private final McpToolCatalog toolCatalog;

    @Override
    public Map<String, Object> apply(MultiAgentState state){
        String request = ChatMessageTexts.lastUserText(state.messages());
        List<McpTool> tools = toolCatalog.list(clientKey);
        String toolList = renderTools(tools);
        ExpertAgent.ExpertProposal proposal;
        try(var ignored = AiUserContext.open(state.userId())) {
            proposal = expertAgent.propose(role, toolList, state.memoryContext(), request);
        } catch (Exception e){
            log.error("专家 {} 处理失败", role, e);
            return Map.of(
                    MultiAgentState.MESSAGES, AiMessage.from("[" + role + "] 处理失败：" + e.getMessage()),
                    MultiAgentState.PROPOSED_TOOL, ""
            );
        }
        String tool = normalizeTool(proposal.tool(), tools, request);
        log.info("专家 {} 提议工具: {} 参数: {}", role, tool, proposal.arguments());
        return Map.of(
                MultiAgentState.MESSAGES, AiMessage.from("[" + role + "] " + safe(proposal.reply())),
                MultiAgentState.PROPOSED_TOOL, tool,
                MultiAgentState.PROPOSED_ARGS, toJson(proposal.arguments()),
                MultiAgentState.PROPOSED_BY, clientKey
        );
    }

    private String normalizeTool(String proposed, List<McpTool> available, String request){
        if(proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())){
            return NO_TOOL;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if(!exists){
            log.warn("专家 {} 提议了一个不在清单里的工具: [{}]。可用: {}。请求: {}",
                    role, trimmed, available.stream().map(McpTool::name).toList(), request);
            return NO_TOOL;
        }
        return trimmed;
    }

    private String renderTools(List<McpTool> tools){
        if(tools.isEmpty()){
            return "(本次没有可用工具，只给出文字意见)";
        }
        StringBuilder sb = new StringBuilder();
        for(McpTool tool: tools){
            sb.append("- ").append(tool.name())
                    .append("[危险等级：").append(tool.risk()).append("]\n")
                    .append(" 说明：").append(safe(tool.description())).append('\n')
                    .append(" 参数：").append(tool.parameters()).append('\n');
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

