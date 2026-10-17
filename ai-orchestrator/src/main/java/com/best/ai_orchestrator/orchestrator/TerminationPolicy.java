package com.best.ai_orchestrator.orchestrator;

import com.best.ai_orchestrator.core.Plan;
import com.best.ai_orchestrator.core.RunState;

/**
 * 终止条件 —— <b>组合式，而且必须有一条兜底</b>。
 *
 * <p>只有"模型说完成了"是不够的：它会转不停。所以这里并列检查四件事，
 * 任何一条成立就收敛，并<b>记录命中的是哪一条</b>（这条原因会进事件流）。
 *
 * <p>四条按"从软到硬"排列：
 * <ol>
 *   <li><b>全部子任务完成</b> —— 正常收敛</li>
 *   <li><b>连续 N 轮没有新进展</b> —— 增量式判据。它防的是"每轮都有活干，
 *       但干的都是重复的"：循环迭代追加的子任务如果总是"再确认一次"，
 *       已完成数就不涨，这时该停</li>
 *   <li><b>预算耗尽</b> —— 成本兜底</li>
 *   <li><b>达到最大轮次</b> —— 最后的防呆墙。它保证任何逻辑漏洞
 *       都不会让系统真的跑飞</li>
 * </ol>
 */
public class TerminationPolicy {

    public record Decision(boolean stop, String reason) {
        static Decision go() {
            return new Decision(false, "");
        }

        static Decision stop(String reason) {
            return new Decision(true, reason);
        }
    }

    private final int maxRounds;
    private final int noProgressLimit;

    public TerminationPolicy(int maxRounds, int noProgressLimit) {
        this.maxRounds = Math.max(1, maxRounds);
        this.noProgressLimit = Math.max(1, noProgressLimit);
    }

    public Decision evaluate(Plan plan, RunState state) {
        if (plan.allDone()) {
            return Decision.stop("全部子任务完成");
        }
        if (state.getNoProgressRounds() >= noProgressLimit) {
            return Decision.stop("连续 " + noProgressLimit + " 轮没有新的子任务完成");
        }
        if (state.getBudget().exhausted()) {
            return Decision.stop("token 预算耗尽");
        }
        if (state.getRounds() >= maxRounds) {
            return Decision.stop("达到最大轮次 " + maxRounds);
        }
        return Decision.go();
    }
}
