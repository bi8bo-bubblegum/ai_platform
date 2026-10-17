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
