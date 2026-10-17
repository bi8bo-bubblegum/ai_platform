package com.best.ai_orchestrator.core;

import com.best.ai_orchestrator.observability.RunEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次运行的全部状态 —— <b>它是"暂停 / 恢复"能成立的全部原因</b>。
 *
 * <p><b>为什么这套东西不需要图引擎。</b> 编排器是一个普通 Java 方法，
 * 它可以在主循环的任何一个点上识别出"这次要停下来问人"，然后 {@code return}。
 * 把这个状态对象存下来，恢复就是"再调一次编排器，让它从这里继续"。
 *
 * <p>图引擎提供的是"<b>任意拓扑</b>的条件中断 + 检查点"。
 * 而这里需要的只是"<b>一条主循环上的</b>条件中断" ——
 * 那就用一个可序列化的状态对象解决，不必引入一个框架，
 * 也不必为它付出"状态机 / 顶点 / 边 / 编译期图校验"的全部复杂度。
 *
 * <p>⚠️ <b>为了能存 Redis，这里刻意是 POJO + 无参构造 + setter</b>。
 * ★ <b>访问器必须用标准命名（getXxx/setXxx）</b> —— Jackson 只认这个。
 * 曾手写 record 风格的 {@code runId()} 读方法，序列化出来只剩
 * {@code {"lastDoneCount":0}}（唯一一个标准命名的 getter），其余 11 个字段全丢；
 * 嵌套的 {@link Budget} 更是直接抛 {@code InvalidDefinitionException}。
 * <b>症状：审批暂停后存进 Redis 的是残骸，恢复回来是个空壳</b> ——
 * 而编译一路绿灯。现在用 Lombok 生成标准访问器，并按生态约定来。
 */
@Getter
@Setter
public class RunState {

    private String runId;
    private String request;
    private RunContext context;
    private Budget budget;
    private Plan plan;
    private List<RunEvent> events = new ArrayList<>();

    /** 主循环已经转了几轮 */
    private int rounds;

    /** 连续多少轮"已完成数没有增加" —— 增量式终止判定的输入 */
    private int noProgressRounds;
    private int lastDoneCount;

    private String terminationReason;

    public RunState() {
    }

    public RunState(String runId, String request, RunContext context, Budget budget) {
        this.runId = runId;
        this.request = request;
        this.context = context;
        this.budget = budget;
    }

    /**
     * 一次运行的"外部事实" —— 整条链路上不变，且不来自模型。
     *
     * @param memoryText       长期记忆召回（用户是谁）。<b>只召一次</b>，
     *                         所有子任务共用 —— 它属于"用户是谁"，
     *                         不属于"某个子任务"
     * @param conversationText 会话历史（这次对话刚才聊了什么），渲染后的文本。
     *                         ★ <b>只给 Planner 和 Synthesizer 看，
     *                         不给 Worker 看</b> —— 后者只看自己子任务的 scope。
     *                         这正是上下文隔离：指代消解的责任在规划器，
     *                         执行者拿到的是已经消解好的完整描述
     */
    public record RunContext(String userId,
                             String sessionId,
                             String assistantId,
                             String memoryText,
                             String conversationText) {

        public static RunContext of(String userId, String sessionId, String assistantId) {
            return new RunContext(userId, sessionId, assistantId, "", "");
        }
    }

    // ---- 业务方法（非访问器，Lombok 不生成） ----

    public void beginRound() {
        rounds++;
    }

    /** 每轮结束时调用：更新"有没有新进展"。★ 这是增量式终止判定的输入 */
    public void endRound() {
        int now = plan == null ? 0 : plan.doneCount();
        if (now == lastDoneCount) {
            noProgressRounds++;
        } else {
            noProgressRounds = 0;
            lastDoneCount = now;
        }
    }

    public void addEvent(RunEvent event) {
        events.add(event);
    }

    public RunReport report() {
        int total = plan == null ? 0 : plan.size();
        int done = plan == null ? 0 : plan.doneCount();
        int failed = plan == null ? 0 : (int) plan.outcomes().stream().filter(TaskOutcome::isFailed).count();
        return new RunReport(runId, rounds, total, done, failed,
                budget == null ? 0 : budget.getUsedTokens(),
                budget == null ? 0 : budget.getMaxTokens(),
                terminationReason,
                // ★ 事件必须随报告出去 —— 否则"可观测四事件"只有内部有、外部没出口：
                //   运行时一收敛 RunState 就被删掉，事件连翻 Redis 都翻不到。
                //   防御性拷贝：交出去的东西不该是 state 里的活引用（33 号同款思维）。
                List.copyOf(events));
    }

    /**
     * 运行报告 —— 对外只需要这几个数字 + 事件流，不需要把整个 state 交出去。
     *
     * <p>⚠️ {@code events} 加在**最后一位**：RunReport 是实时构造的、不落库，
     * 所以现在加组件零存量成本；但即便如此，新组件也只加在末尾 ——
     * 中间插一个字段会让所有按位置读它的代码静默错位。
     */
    public record RunReport(String runId,
                            int rounds,
                            int tasksTotal,
                            int tasksDone,
                            int tasksFailed,
                            int tokensUsed,
                            int tokenBudget,
                            String terminationReason,
                            List<RunEvent> events) {
    }

    /**
     * 事件列表的 setter。<b>手写而不用 Lombok 生成</b> ——
     * Lombok 生成的是裸赋值，反序列化进 null 会让后续 {@code addEvent} NPE；
     * 这里兜底成空列表。
     */
    public void setEvents(List<RunEvent> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }
}
