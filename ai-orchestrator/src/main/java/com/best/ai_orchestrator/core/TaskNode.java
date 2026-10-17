package com.best.ai_orchestrator.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个子任务的完整状态：规格 + 私有上下文 + 结果 + 待审批 + 人的决定。
 *
 * <p><b>它必须可序列化</b>（无参构造 + setter）—— 暂停时整个 {@link RunState}
 * 要能存下来，"恢复时从一半继续"靠的就是这里的 {@code turns}。
 *
 * <p>⚠️ <b>为什么是 class + setter 而不是 record</b>：
 * record 的字段不可变，而这里的 {@code turns} 是<b>就地追加</b>的；
 * 而且 Jackson 反序列化 record 需要 {@code @JsonCreator} 或 {@code ParameterNamesModule}。
 * 判据始终是<b>"谁要写它"</b>：反序列化器要写，就用 class + setter。
 *
 * <p>★ <b>访问器用 Lombok 生成标准命名</b> —— 这个对象要进 Redis，
 * Jackson 只认 getXxx/setXxx（record 风格的 {@code spec()} 它不认，
 * 详见 {@link Budget} 类注释里那次事故）。
 */
@Getter
@Setter
public class TaskNode {

    private TaskSpec spec;

    /** <b>这个子任务私有的上下文</b> —— 别的子任务看不见 */
    private List<WorkerTurn> turns = new ArrayList<>();

    private TaskOutcome outcome;

    /** 非 null 表示这个子任务卡在人工审批上 */
    private PendingApproval pending;

    /** 人对 {@link #pending} 的回答：null = 还没答 */
    private Boolean approved;

    public TaskNode() {
    }

    public TaskNode(TaskSpec spec) {
        this.spec = spec;
    }

    /**
     * 是否已完成。<b>派生属性，不参与序列化</b> —— 它的值就是 {@code outcome != null}，
     * 存下来反而多一处可能不一致的真相。
     *
     * <p>⚠️ 必须加 {@code @JsonIgnore}：{@code isXxx} 符合 Jackson 的 is-getter 约定，
     * 不忽略的话序列化会多出一个 {@code "done"} 字段，反序列化时找不到对应的
     * {@code setDone} → {@code UnrecognizedPropertyException}，整条恢复链路断在这里。
     */
    @JsonIgnore
    public boolean isDone() {
        return outcome != null;
    }

    public void addTurn(WorkerTurn turn) {
        turns.add(turn);
    }

    public void finish(TaskOutcome o) {
        this.outcome = o;
        clearDecision();
    }

    public void awaitApproval(PendingApproval p) {
        this.pending = p;
        this.approved = null;
    }

    /** 记下人的回答。恢复时调用 —— 注意它<b>不改</b> pending，由执行器结算后清除 */
    public void decide(boolean ok) {
        this.approved = ok;
    }

    public void clearDecision() {
        this.pending = null;
        this.approved = null;
    }

    /** 渲染私有上下文，给执行者看 */
    public String renderHistory() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkerTurn t : turns) {
            sb.append(t.render()).append('\n');
        }
        return sb.toString();
    }

    // ---- Jackson ----
    // 只有 setTurns 手写：它要 null 保护（反序列化进 null 会让 addTurn NPE）。
    // Lombok 检测到已存在同名 setter 会跳过这个字段，其余 setter 自动生成。

    public void setTurns(List<WorkerTurn> turns) {
        this.turns = turns == null ? new ArrayList<>() : turns;
    }
}
