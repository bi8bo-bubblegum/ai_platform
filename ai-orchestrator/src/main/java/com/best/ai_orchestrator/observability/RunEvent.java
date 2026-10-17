package com.best.ai_orchestrator.observability;

import java.time.Instant;
import java.util.Locale;

/**
 * 运行事件 —— <b>四类，构成一条完整的因果链</b>。
 *
 * <p>"这个答案为什么长这样"这个问题，只能靠这四类事件回答。
 * 缺任何一类，出事时你就得靠猜：
 *
 * <ul>
 *   <li>{@code TASK_DISPATCHED} —— 派给了谁、scope 是什么</li>
 *   <li>{@code TASK_COMPLETED} —— 花了多少 token、置信度多少、摘要是什么</li>
 *   <li>{@code LOOP_CONVERGED} —— <b>为什么停</b>、这一轮有没有增量</li>
 *   <li>{@code RUN_FAILED} —— 哪一步坏了、错误原文</li>
 * </ul>
 *
 * <p><b>为什么单独一个包。</b> 事件是"对外承诺"的一部分 ——
 * 将来接 OpenTelemetry / 落盘 / 推流，改的是这一个包，不动编排逻辑。
 * 如果把它散在各处 {@code log.info}，那就永远只能靠 grep 日志。
 */
public record RunEvent(Instant at, Kind kind, String taskId, String detail, int tokens) {

    public enum Kind {
        TASK_DISPATCHED,
        TASK_COMPLETED,
        LOOP_CONVERGED,
        RUN_FAILED
    }

    public static RunEvent dispatched(String taskId, String scope) {
        return new RunEvent(Instant.now(), Kind.TASK_DISPATCHED, taskId, scope, 0);
    }

    public static RunEvent completed(String taskId, String summary, double confidence, int tokens) {
        return new RunEvent(Instant.now(), Kind.TASK_COMPLETED, taskId,
                "置信度=" + String.format(Locale.ROOT, "%.2f", confidence) + " 摘要=" + summary, tokens);
    }

    public static RunEvent converged(String reason, int doneCount, int total) {
        return new RunEvent(Instant.now(), Kind.LOOP_CONVERGED, null,
                reason + "（已完成 " + doneCount + "/" + total + "）", 0);
    }

    public static RunEvent failed(String taskId, String error) {
        return new RunEvent(Instant.now(), Kind.RUN_FAILED, taskId, error, 0);
    }

    public String render() {
        return "%s [%s] %s".formatted(at, kind, detail) + (taskId == null ? "" : " @" + taskId);
    }
}
