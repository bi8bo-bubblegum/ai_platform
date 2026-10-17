package com.best.ai_orchestrator.orchestrator;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_orchestrator.config.OrchestratorProperties;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.core.Budget;
import com.best.ai_orchestrator.core.Plan;
import com.best.ai_orchestrator.core.RunOutcome;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.core.TaskOutcome;
import com.best.ai_orchestrator.core.TaskSpec;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.observability.RunEvent;
import com.best.ai_orchestrator.plan.Planner;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排器 —— <b>整个模块唯一的控制流</b>。
 *
 * <p><b>它是一段普通 Java 的主循环，不是一张图。</b> 这是本模块最核心的设计决定，
 * 理由是：
 * <ul>
 *   <li>你要的形状是"所有请求相同、只是内容不同" —— 那是<b>循环</b>，不是图。
 *       图引擎真正提供的是"任意拓扑 + 编译期图校验 + 可视化"。</li>
 *   <li>"可中断 / 可暂停 / 可恢复"<b>不需要图引擎</b>：一个可序列化的
 *       {@link RunState} + sealed {@link RunOutcome} 就够。
 *       {@link #run} 和 {@link #resume} 走的是<b>同一个</b> {@link #drive}。</li>
 *   <li>中断 = {@code return}。<b>代码放哪就能停在哪</b> —— 循环内部也行。
 *       如果循环住在图里，中断就只能是节点级的（审批最多拦住第一次，
 *       这是本项目 25 号踩过的坑）。</li>
 * </ul>
 *
 * <p><b>主循环每一步：</b>
 * <pre>
 *   while (true) {
 *       ① 终止判定（全部完成 / 无新进展 / 预算耗尽 / 轮次上限）—— 记录命中哪条
 *       ② 取下一批（依赖已满足的才同批）
 *       ③ 派发执行（顺序或并行；每个子任务有自己的上下文）
 *       ④ 记账 + 事件
 *       ⑤ 若有人要审批 → 存 state → return Paused        ★ 工具没有执行
 *       ⑥ 循环迭代：问规划器"基于已有结论，还要补什么" → plan.add(...)
 *   }
 * </pre>
 *
 * <p>★ <b>{@link RunStateStore} 的存取收在编排器内部</b>（{@code ⑤}），
 * 调用方拿不到"忘了存"的机会 —— 把容易漏的责任从"人的纪律"变成"代码结构"。
 * 这和 33 号把 {@code SessionKey} 收进构造器是同一个手法。
 */
@Slf4j
@Component
public class Orchestrator {

    private final Planner planner;
    private final Synthesizer synthesizer;
    private final TaskDispatcher dispatcher;
    private final TerminationPolicy policy;
    private final RunStateStore store;
    private final MemoryAccess memory;
    private final OrchestratorProperties props;
    private final Map<String, AgentRole> roles;

    public Orchestrator(Planner planner,
                        Synthesizer synthesizer,
                        TaskDispatcher dispatcher,
                        TerminationPolicy policy,
                        RunStateStore store,
                        MemoryAccess memory,
                        OrchestratorProperties props,
                        Map<String, AgentRole> roles) {
        // ★ 角色表为空 = 没人告诉编排器"有哪些专家可用" → 现在就失败。
        // 放到运行期才暴露的话，症状是"规划器随便派个人、然后全都失败"，
        // 排查时要翻到很后面才发现根因是没角色
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何角色可用。角色是业务知识，请在业务模块里定义 RoleRules 实现，"
                            + "并通过 @Bean 提供 Map<String, AgentRole>（key = 角色名）");
        }
        this.planner = planner;
        this.synthesizer = synthesizer;
        this.dispatcher = dispatcher;
        this.policy = policy;
        this.store = store;
        this.memory = memory;
        this.props = props;
        this.roles = roles;
    }

    /**
     * 开始一次运行。
     *
     * @param request 本次用户消息（<b>原始</b>，未消解指代）
     */
    public RunOutcome run(String request, RunState.RunContext ctx) {
        String runId = UUID.randomUUID().toString();
        Budget budget = new Budget(props.getTokenBudget(), props.getPerTaskTokenBudget());
        RunState state = new RunState(runId, request, ctx, budget);

        // ---- 规划 ----
        List<TaskSpec> specs;
        try {
            specs = toSpecs(planner.plan(rolesText(), ctx.conversationText(), request).tasks());
        } catch (Exception e) {
            log.error("规划失败 runId={}", runId, e);
            return new RunOutcome.Failed(runId, "规划失败: " + e.getMessage(), state.report());
        }
        state.setPlan(new Plan(specs.stream().map(TaskNode::new).toList()));
        if (state.getPlan().size() == 0) {
            return new RunOutcome.Failed(runId, "规划器没有产出任何子任务", state.report());
        }
        log.info("运行开始 runId={} 子任务数={}", runId, state.getPlan().size());
        return drive(state);
    }

    /**
     * 恢复一次被审批打断的运行。
     *
     * <p><b>只回答 {@code taskId} 指定的那一个待审批</b>，不是全部。
     * 同批里如果有两个子任务都在等审批，用户需要分别批两次 ——
     * 一把全批会把"第二个"也放过去，那是错误的。
     */
    public RunOutcome resume(RunState state, String taskId, boolean approved) {
        TaskNode node = state.getPlan().getNodes().stream()
                .filter(n -> n.getSpec().id().equals(taskId))
                .findFirst()
                .orElse(null);

        if (node == null || node.getPending() == null) {
            log.warn("resume 指定的 taskId={} 不是待审批状态，忽略", taskId);
        } else if (node.getApproved() != null) {
            log.warn("taskId={} 已经被回答过，忽略重复审批", taskId);
        } else {
            node.decide(approved);
        }
        return drive(state);
    }

    /** ★ 主循环。run 和 resume 都走这里 —— "恢复"就是同一个 while 再转一次 */
    private RunOutcome drive(RunState state) {
        Plan plan = state.getPlan();
        String memoryId = MemoryAccess.memoryIdOf(
                state.getContext().assistantId(), state.getContext().userId(), state.getContext().sessionId());

        try (var ignored = AiUserContext.open(state.getContext().userId())) {
            while (true) {
                // ---- ① 终止判定 ----
                TerminationPolicy.Decision decision = policy.evaluate(plan, state);
                if (decision.stop()) {
                    converge(state, plan, decision.reason());
                    break;
                }

                // ---- ② 取下一批（预算紧张时降并行度） ----
                state.beginRound();
                int parallel = state.getBudget().shouldDowngrade() ? 1 : props.getMaxParallel();
                List<TaskNode> batch = plan.nextBatch(parallel);
                if (batch.isEmpty()) {
                    // 依赖成环，或者没有可执行的。不要死循环
                    converge(state, plan, "没有可执行的子任务（依赖可能成环）");
                    break;
                }
                batch.forEach(n -> state.addEvent(RunEvent.dispatched(n.getSpec().id(), n.getSpec().goal())));

                // ---- ③ 执行 ----
                TaskDispatcher.DispatchResult result = dispatcher.dispatch(batch, state);

                // ---- ④ 记账 + 事件 ----
                for (TaskNode n : batch) {
                    if (n.isDone()) {
                        TaskOutcome o = n.getOutcome();
                        state.getBudget().consume(o.tokensUsed());
                        state.addEvent(RunEvent.completed(o.taskId(),
                                o.isFailed() ? o.error() : o.summary(), o.confidence(), o.tokensUsed()));
                    }
                }

                // ---- ⑤ 有人要审批 → 存 state，return ----
                if (result instanceof TaskDispatcher.DispatchResult.Paused paused) {
                    state.setTerminationReason(null);
                    store.save(state.getRunId(), state);
                    log.info("运行暂停 runId={} 等待审批 task={} tool={}",
                            state.getRunId(), paused.pending().taskId(), paused.pending().tool());
                    return new RunOutcome.Paused(state.getRunId(), paused.pending(), state, state.report());
                }

                // ---- ⑥ 循环迭代 ----
                state.endRound();
                if (!plan.allDone() && !state.getBudget().exhausted()) {
                    refine(state, plan);
                }
            }
        }

        return finish(state, memoryId);
    }

    /**
     * 循环迭代：问规划器"基于已有结论，还要补什么子任务"。
     *
     * <p>★ <b>这是本模块与"并行版 prompt chaining"的分界线。</b>
     * 失败时<b>按"不再追加"处理</b>，不让一次 refine 失败毁掉整轮 ——
     * 已经做完的子任务结果仍然有效，只是少了补洞的机会。
     */
    private void refine(RunState state, Plan plan) {
        try {
            List<TaskSpec> extra = toSpecs(planner.refine(
                    rolesText(),
                    state.getContext().conversationText(),
                    plan.renderOutcomes(),
                    state.getRequest()).tasks());
            plan.add(extra);
        } catch (Exception e) {
            log.warn("循环迭代失败，按不再追加处理。runId={}", state.getRunId(), e);
        }
    }

    private void converge(RunState state, Plan plan, String reason) {
        state.setTerminationReason(reason);
        state.addEvent(RunEvent.converged(reason, plan.doneCount(), plan.size()));
        log.info("运行收敛 runId={} 原因={} 已完成={}/{}",
                state.getRunId(), reason, plan.doneCount(), plan.size());
    }

    /** 汇总 + 触发长期记忆写入 */
    private RunOutcome finish(RunState state, String memoryId) {
        Plan plan = state.getPlan();
        String answer;
        try {
            answer = synthesizer.synthesize(state.getRequest(),
                    state.getContext().conversationText(), plan.renderOutcomes());
        } catch (Exception e) {
            log.error("汇总失败，退化为按子任务罗列。runId={}", state.getRunId(), e);
            // ★ 汇总失败也要给用户一个答案 —— 退化为罗列，不要整个失败
            answer = "（汇总环节出错，以下是各子任务的原始结论）\n" + plan.renderOutcomes();
        }

        // 会话历史 + 长期记忆都由编排器写，不留给调用方 —— "别忘了存"是人的纪律，
        // 收进代码结构才不会漏。漏了的症状（第二轮没有上下文）在单轮测试里完全看不出来
        memory.appendExchange(memoryId, state.getRequest(), answer);
        memory.remember(memoryId, List.of(UserMessage.from(state.getRequest()), AiMessage.from(answer)));
        store.remove(state.getRunId());

        return new RunOutcome.Completed(state.getRunId(), answer, state.report());
    }

    // ---- 小工具 ----

    private List<TaskSpec> toSpecs(List<Planner.PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .map(t -> new TaskSpec(t.id(), t.goal(), t.expectedOutput(), t.boundaries(),
                        t.roleName(), t.dependsOn(), props.getMaxToolRounds()))
                .toList();
    }

    /** 渲染"可用角色"清单，给规划器看 */
    private String rolesText() {
        Map<String, AgentRole> sorted = new LinkedHashMap<>(roles);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, role) ->
                sb.append("- ").append(key).append("：").append(role.description()).append('\n'));
        return sb.toString();
    }
}
