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
