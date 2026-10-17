package com.best.ai_orchestrator.core;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 计划：子任务集合 + 依赖关系 + 执行进度。
 *
 * <p><b>依赖关系唯一的作用是决定"哪些可以同时跑"</b> ——
 * 它是并行的<b>前置条件</b>，不是流程图。<b>没有依赖的子任务才会被放进同一批。</b>
 *
 * <p>这也是对 Cognition 那条批评的正面回应：他们的反例（两个子任务画风不一致）
 * 之所以失败，是因为两个子任务<b>实际上互相依赖</b>，却被当成独立的并行了出去。
 * 这里的做法是：<b>依赖关系必须显式写出来</b>，写不出来的说明它们本来就该串起来。
 *
 * <p>★ <b>{@link #add(List)} 支撑循环迭代</b>：编排者看完已有结论，可以追加新的子任务。
 * 这是本模块与"并行版 prompt chaining"的分界线。
 */
@Slf4j
@Getter
@Setter
public class Plan {

    private List<TaskNode> nodes = new ArrayList<>();

    public Plan() {
    }

    public Plan(List<TaskNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    /**
     * nodes 的 setter。<b>手写而不用 Lombok 生成</b> —— 要 null 保护
     * （反序列化进 null 会让 {@code nextBatch} 直接 NPE）。
     */
    public void setNodes(List<TaskNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    /**
     * 追加子任务（循环迭代用）。
     *
     * <p><b>id 已存在的直接跳过</b>，而不是覆盖或改名：
     * <ul>
     *   <li>覆盖 → 会丢掉那个子任务已经做完的结果</li>
     *   <li>改名 → {@code dependsOn} 会指向一个不存在的 id</li>
     * </ul>
     * 两种错都比"少加一个子任务"严重，所以选最保守的那个并打一条 warn。
     *
     * @return 真正加进去的数量
     */
    public int add(List<TaskSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (TaskSpec spec : specs) {
            if (find(spec.id()).isPresent()) {
                log.warn("追加的子任务 id={} 已存在，跳过（覆盖会丢结果，改名会断依赖）", spec.id());
                continue;
            }
            nodes.add(new TaskNode(spec));
            added++;
        }
        if (added > 0) {
            log.info("循环迭代：追加了 {} 个子任务", added);
        }
        return added;
    }

    private Optional<TaskNode> find(String id) {
        return nodes.stream().filter(n -> n.getSpec().id().equals(id)).findFirst();
    }

    /**
     * 依赖是否已满足。
     *
     * <p>依赖<b>不存在</b>时视为满足 —— 规划器偶尔会编一个不存在的依赖 id，
     * 那种情况下"当成满足"最坏是顺序乱了，而"当成不满足"会让整个计划死锁。
     * <b>两种错的代价不对称，所以选前者并打一条 warn。</b>
     */
    private boolean dependencySatisfied(String depId) {
        Optional<TaskNode> dep = find(depId);
        if (dep.isEmpty()) {
            log.warn("子任务依赖了一个不存在的 id: {} —— 按已满足处理，避免死锁", depId);
            return true;
        }
        return dep.get().isDone();
    }

    /** 下一批可以执行（自己没做完、依赖全部满足）的子任务 */
    public List<TaskNode> nextBatch(int maxParallel) {
        List<TaskNode> ready = new ArrayList<>();
        for (TaskNode n : nodes) {
            if (n.isDone()) {
                continue;
            }
            if (n.getSpec().dependsOn().stream().allMatch(this::dependencySatisfied)) {
                ready.add(n);
                if (ready.size() >= Math.max(1, maxParallel)) {
                    break;
                }
            }
        }
        return ready;
    }

    public boolean allDone() {
        return nodes.stream().allMatch(TaskNode::isDone);
    }

    public int doneCount() {
        return (int) nodes.stream().filter(TaskNode::isDone).count();
    }

    public int size() {
        return nodes.size();
    }

    /** 所有已产出的结果，按计划顺序 —— 汇总器和规划器的输入 */
    public List<TaskOutcome> outcomes() {
        return nodes.stream().map(TaskNode::getOutcome).filter(Objects::nonNull).toList();
    }

    /** 首尾相接的浓缩结论。★ 循环迭代和汇总都靠它 */
    public String renderOutcomes() {
        List<TaskOutcome> list = outcomes();
        if (list.isEmpty()) {
            return "(还没有任何子任务产出)";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskOutcome o : list) {
            sb.append(o.render()).append('\n');
        }
        return sb.toString();
    }
}
