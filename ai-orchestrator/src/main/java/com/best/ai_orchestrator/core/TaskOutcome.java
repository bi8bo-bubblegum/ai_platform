package com.best.ai_orchestrator.core;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Locale;

/**
 * <b>交接协议</b> —— 执行者交回给编排器的东西。
 *
 * <p>这个类是"多智能体能不能用"的分水岭。三条来自生产实践的要求都落在这里：
 *
 * <ol>
 *   <li><b>浓缩，不回传原文。</b> 执行者读了 40 篇文档，交回的是"结论 + 依据位置"，
 *       不是 40 篇全文。不这么做，编排器的上下文会被子任务撑爆 ——
 *       而"撑爆"是这套架构最常见的死法。</li>
 *
 *   <li><b>必须带 {@code confidence} 和 {@code uncovered}。</b>
 *       编排器靠这两个字段判断"哪里还需要补一轮"（循环迭代就靠它）。
 *       没有它们，编排器只能猜 —— 于是它要么过度再派一轮（烧钱），
 *       要么漏掉缺口（答案不全）。</li>
 *
 *   <li>★ <b>失败必须显式标记，不能静默合并。</b>
 *       {@code status=FAILED} 时 {@code error} 必填，而且这条结果会被
 *       <b>原样带进最终汇总</b>（{@code Synthesizer} 的提示词里明确要求"未完成的部分必须告诉用户"）。
 *       静默合并的后果是：答案看起来完整，但缺了一块，而且没人知道。</li>
 * </ol>
 */
public record TaskOutcome(String taskId,
                          TaskStatus status,
                          String summary,
                          List<String> evidence,
                          double confidence,
                          List<String> uncovered,
                          String error,
                          int tokensUsed) {

    public enum TaskStatus {
        /** 做完了，summary 有效 */
        DONE,
        /** <b>没做完</b>。error 必填，summary 为空 */
        FAILED
    }

    public TaskOutcome {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static TaskOutcome done(String taskId, String summary, List<String> evidence,
                                   double confidence, List<String> uncovered, int tokensUsed) {
        return new TaskOutcome(taskId, TaskStatus.DONE, summary, evidence, confidence, uncovered, null, tokensUsed);
    }

    /** ★ 失败也产生一条 outcome —— 而不是被 catch 吞掉 */
    public static TaskOutcome failed(String taskId, String error, int tokensUsed) {
        return new TaskOutcome(taskId, TaskStatus.FAILED, "", List.of(), 0.0, List.of(), error, tokensUsed);
    }

    /**
     * 是否失败。<b>派生属性，不参与序列化</b> —— 它的值就是 {@code status == FAILED}。
     *
     * <p>⚠️ 必须加 {@code @JsonIgnore}：{@code isXxx} 符合 Jackson 的 is-getter 约定，
     * 不忽略的话序列化会多出一个 {@code "failed"} 字段，反序列化 record 时
     * 找不到对应参数 → {@code UnrecognizedPropertyException}。
     * （{@link TaskNode#isDone()} 同款问题，两个都是实测抓出来的。）
     */
    @JsonIgnore
    public boolean isFailed() {
        return status == TaskStatus.FAILED;
    }

    /** 给汇总器和规划器看的渲染。失败的那条会被明确标出来，不会被混在正文里。 */
    public String render() {
        if (isFailed()) {
            return "- [%s] 未完成：%s".formatted(taskId, error);
        }
        StringBuilder sb = new StringBuilder("- [%s] %s".formatted(taskId, summary));
        if (!evidence.isEmpty()) {
            sb.append("\n  依据：").append(String.join("；", evidence));
        }
        if (!uncovered.isEmpty()) {
            sb.append("\n  未覆盖：").append(String.join("；", uncovered));
        }
        sb.append("\n  置信度：").append(String.format(Locale.ROOT, "%.2f", confidence));
        return sb.toString();
    }
}
