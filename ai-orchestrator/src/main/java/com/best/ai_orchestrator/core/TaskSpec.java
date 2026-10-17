package com.best.ai_orchestrator.core;

import java.util.List;
import java.util.UUID;

/**
 * 一个子任务的规格 —— <b>显式 scope</b>。
 *
 * <p>这是它与"给个模糊指令就派出去"的关键区别。生产经验里最反复出现的失败模式之一：
 * <b>不给 scope 的子任务会互相重复劳动、留下空白、或者跑到完全无关的方向上去。</b>
 * 最极端的一个例子是为一个简单查询起了 50 个执行者。
 *
 * <p><b>四个字段缺一不可</b>：
 * <ul>
 *   <li>{@code goal} —— 做什么。<b>它必须是消解过指代的完整描述</b>
 *       （"那它什么时候发货"要由规划器翻成"查订单 12345 的预计发货时间"）</li>
 *   <li>{@code expectedOutput} —— <b>什么样算做完</b>。没有它，执行者不知道什么时候停</li>
 *   <li>{@code boundaries} —— 不要做什么。没有它，执行者会越界</li>
 *   <li>{@code dependsOn} —— 等谁先做完。<b>只有真的要用前一个产出时才写</b>；
 *       写多了会把本来可并行的子任务串起来，白等</li>
 * </ul>
 *
 * @param maxToolRounds 工具轮次上限。<b>上限是刹车，不是目的地</b> ——
 *                      它保证任何逻辑漏洞都不会让某个子任务无限转下去
 */
public record TaskSpec(String id,
                       String goal,
                       String expectedOutput,
                       String boundaries,
                       String roleName,
                       List<String> dependsOn,
                       int maxToolRounds) {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    public TaskSpec {
        if (id == null || id.isBlank()) {
            id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        }
        dependsOn = (dependsOn == null || dependsOn.isEmpty()) ? List.of() : List.copyOf(dependsOn);
        maxToolRounds = maxToolRounds <= 0 ? DEFAULT_MAX_TOOL_ROUNDS : maxToolRounds;
    }

    /** 渲染成给执行者看的"本次任务"段落 */
    public String render() {
        String b = (boundaries == null || boundaries.isBlank()) ? "(无特别限制)" : boundaries;
        return """
                - 目标：%s
                - 期望产出：%s
                - 边界：%s
                """.formatted(goal, expectedOutput, b);
    }
}
