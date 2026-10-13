package com.best.ai_graph.node;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_graph.state.MultiAgentState;
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

@Slf4j
public class ToolExecuteNode implements NodeAction<MultiAgentState>, InterruptibleAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private final McpClientRegister register;

    private final McpToolCatalog toolCatalog;

    private final ToolRisk approvalMinRisk;

    public ToolExecuteNode(McpClientRegister register, McpToolCatalog toolCatalog, ToolRisk approvalMinRisk) {
        this.register = register;
        this.toolCatalog = toolCatalog;
        this.approvalMinRisk = approvalMinRisk;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String tool = state.proposedTool();
        if(isNoTool(tool)){
            return Map.of();
        }
        if(state.approvalDecided() && !state.approved()){
            log.info("工具{}被人工拒绝", tool);
            return finishDone(AiMessage.from("[操作被拒绝] 已取消：" + tool));
        }
        McpClient client = register.get(state.proposedBy());
        if(client == null){
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", state.proposedBy(), tool);
            return finishDone(AiMessage.from("[工具 " + tool + " 未能执行] 未配置 "
                    + state.proposedBy() + " 的MCP通道"));
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(state.proposedArgs())
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, state.proposedBy(), abbreviate(text));
            // ★ 走 finishContinue（不是 finishDone）：保留 proposedTool，
            //   因为条件边要靠它判断"这一轮循环要不要继续"。
            return finishContinue(AiMessage.from(
                    "[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text));
        } catch (Exception e){
            log.error("工具执行异常: tool={} by={} args={}",
                    tool, state.proposedBy(), state.proposedArgs(), e);
            return finishContinue(AiMessage.from("[工具 " + tool + " 执行失败] " + e.getMessage()));
        }
    }

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


    private static String abbreviate(String s){
        if(s == null){
            return "";
        }
        String onLine = s.replaceAll("\\s+", " ");
        return onLine.length() <= 200 ? onLine : onLine.substring(0, 200) + "...(截断)";
    }

    private Map<String, Object> finishContinue(AiMessage message){
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    private Map<String, Object> finishDone(AiMessage message){
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.PROPOSED_TOOL, NO_TOOL,
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    private static boolean isNoTool(String tool){
        return tool == null || tool.isBlank() || NO_TOOL.equals(tool);
    }

    }
