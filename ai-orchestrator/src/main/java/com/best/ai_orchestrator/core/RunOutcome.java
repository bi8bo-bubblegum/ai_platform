package com.best.ai_orchestrator.core;

/**
 * 一次运行的结局 —— <b>sealed，三个分支穷尽</b>。
 *
 * <p><b>它的形状就是"为什么不需要图引擎"的答案。</b>
 * 编排器只有一个出口，而出口有三种：
 * <ul>
 *   <li>{@link Completed} —— 收敛了，有答案</li>
 *   <li>{@link Paused} —— <b>停下等人批准，把整个 state 一并交出去</b>。
 *       调用方负责把它存起来（内存 / Redis / 数据库都行），人答完之后
 *       再用同一个 state 调 {@code resume}</li>
 *   <li>{@link Failed} —— 还没能进入执行就失败了（规划器连续出错）</li>
 * </ul>
 *
 * <p>用 sealed interface 而不是"一个带 status 字段的类"，是为了让
 * <b>调用方必须处理"暂停"这一支</b> —— 漏了它编译器会报错。
 * 而漏了它的后果是"前端看到 done，实际活没干"（这个坑本项目踩过）。
 */
public sealed interface RunOutcome {

    String runId();

    /** 收敛。answer 是最终答案（未完成的子任务已在其中被标注） */
    record Completed(String runId, String answer, RunState.RunReport report) implements RunOutcome {
    }

    /** <b>停下等人批准。</b> state 必须由调用方持久化，恢复时原样交回 */
    record Paused(String runId, PendingApproval pending, RunState state, RunState.RunReport report)
            implements RunOutcome {
    }

    /** 还没能进入执行就失败了。<b>不是子任务失败</b> —— 那种情况走 Completed */
    record Failed(String runId, String error, RunState.RunReport report) implements RunOutcome {
    }
}
