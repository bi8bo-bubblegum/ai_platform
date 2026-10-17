package com.best.ai_orchestrator.plan;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * 规划器 —— 把用户请求拆成若干<b>带显式 scope</b> 的子任务；以及在看到已有结论后，
 * 决定<b>要不要追加</b>新的子任务。
 *
 * <p><b>两个方法分别对应"任务分解"和"循环迭代"这两个构件。</b>
 * 只有第一个、没有第二个，这套东西就退化成一个并行版的 prompt chaining ——
 * 那正是 Anthropic 五式里最便宜的第一式，花 3.75 倍 token 只买到一个并行，不划算。
 *
 * <p>提示词里有三条来自生产经验的硬约束，每一条都对应一次真实的翻车：
 * <ol>
 *   <li><b>规模随复杂度缩放。</b> 不加这条，会出现"为简单查询起 50 个执行者"。
 *       原文的说法是要把扩展规则<b>写进提示词</b>，而不是靠模型自觉。</li>
 *   <li><b>子任务必须带边界。</b> 不加这条，会出现"几个子任务重复做同一件事，同时留下空白"。</li>
 *   <li><b>只有真要用前一个产出时才写 dependsOn。</b> 写多了会把本来能并行的子任务串起来，白等。</li>
 * </ol>
 *
 * <p>★ <b>{@code goal} 必须是消解过指代的完整描述</b> ——
 * "那它什么时候发货"要翻成"查订单 12345 的预计发货时间"。
 * <b>指代消解的责任在规划器，不在执行者</b>：执行者看不到对话历史（那是上下文隔离的一部分），
 * 所以它只能看到规划器翻译好的东西。
 */
public interface Planner {

    /**
     * 初次规划。
     *
     * @param conversation 渲染后的会话历史。<b>规划器是唯一需要它的地方之一</b> ——
     *                     没有它，第二轮问"那它什么时候发货"时，规划器不知道"它"是什么
     */
    @SystemMessage("""
            你是任务规划器。把用户的请求拆成若干子任务，每个子任务交给下面某个角色。

            ## 可用角色
            {{roles}}

            ## 本次对话到目前为止的内容（用于理解指代，如「它」「那个」）
            {{conversation}}

            ## 拆分原则
            1. **规模随复杂度缩放**：如果一句话就能回答，就只拆 1 个子任务。
               需要汇集多个来源的复杂请求，才拆 2~4 个。
               不要为了显得周全而多拆 —— 每多一个子任务都是真金白银。
            2. **每个子任务都必须有明确边界**：目标、期望产出、不要做什么。
               模糊的指令会让执行者跑偏，或者几个子任务做重复的事。
            3. **只有确实要用前一个子任务的产出时，才写 dependsOn。**
               互相独立的子任务会被并行执行，写错依赖只会让它白等。
            4. **goal 必须是自包含的完整描述**：把「它」「那个」这类指代
               依据上面的对话历史替换成具体对象。执行者看不到对话历史。
            5. **不要把需要你自己判断的事拆出去**，只拆「需要查数据 / 查资料」的部分。

            ## 输出
            只输出结构化结果，不要解释、不要附加文字。
            """)
    PlanOutput plan(@V("roles") String roles,
                    @V("conversation") String conversation,
                    @UserMessage String request);

    /**
     * 循环迭代 —— 看完已有结论，决定要不要<b>追加</b>子任务。
     *
     * <p>★ <b>这是本模块与"并行版 prompt chaining"的分界线。</b>
     * Anthropic 的 LeadResearcher 明确有这一步：
     * "it can create additional subagents or refine its strategy"。
     *
     * <p><b>提示词要求它看 {@code uncovered} 和低置信度的结论</b> ——
     * 那两类正是"哪里还需要补一轮"的信号。没有这些字段，它只能瞎猜。
     *
     * @param outcomes 已有子任务的浓缩结论（含置信度和未覆盖项）
     * @return 要追加的子任务。<b>返回空数组表示"不需要再补"</b>
     */
    @SystemMessage("""
            你是任务规划器。下面是这次任务已经完成的子任务及其结论。

            ## 可用角色
            {{roles}}

            ## 本次对话到目前为止的内容
            {{conversation}}

            ## 已完成的子任务及其结论
            {{outcomes}}

            ## 现在要判断
            基于已有结论，回答用户最初的问题是否还缺什么。

            **追加新子任务的判据（满足任一条才追加）：**
            - 某个子任务的「未覆盖」项正好是用户问题需要的
            - 某两个子任务的结论互相矛盾，需要第三个去交叉验证
            - 某个结论的置信度很低（低于 0.6），而它又是关键依据

            **不要追加的情况（更重要）：**
            - 信息已经够了，只是不够漂亮 → 不要追加，让汇总环节去组织语言
            - 只是想再确认一次 → 不要追加，重复劳动
            - 追加的子任务和已完成的某个本质相同 → 不要追加

            如果没有该追加的，就返回空数组。**空数组是完全正常的答案。**

            ## 输出
            只输出结构化结果，不要解释。
            """)
    PlanOutput refine(@V("roles") String roles,
                      @V("conversation") String conversation,
                      @V("outcomes") String outcomes,
                      @UserMessage String request);

    record PlanOutput(List<PlannedTask> tasks) {
    }

    record PlannedTask(
            @Description("短 id，如 t1 / t2。被别的子任务 dependsOn 时引用它")
            String id,

            @Description("这个子任务要做什么。必须自包含：把「它」「那个」这类指代替换成具体对象")
            String goal,

            @Description("什么样算做完。例如：给出三项结论并附来源")
            String expectedOutput,

            @Description("不要做什么。例如：不要修改数据；只给事实，不给建议")
            String boundaries,

            @Description("交给哪个角色。必须是可用角色列表里的名字，原样照抄")
            String roleName,

            @Description("依赖哪些子任务的 id。只有确实要用它的产出时才写；没有依赖就留空数组")
            List<String> dependsOn
    ) {
    }
}
