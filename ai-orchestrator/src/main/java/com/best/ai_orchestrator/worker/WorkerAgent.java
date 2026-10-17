package com.best.ai_orchestrator.worker;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;
import java.util.Map;

/**
 * 执行者 —— <b>只做一轮</b>：给出结论，或者"我要调某个工具"。
 *
 * <p><b>为什么只做一轮。</b> 如果多轮循环写在这里面，编排器就看不见循环边界，
 * 也就无法在两次工具调用之间插人工审批 —— 这是本项目踩过的坑
 * （放在节点内部的循环，审批最多只能拦住第一次）。
 * 循环放在编排器里（就是一段普通 Java 的 while），
 * 于是每一步都是可中断、可记录、可恢复的。<b>而且不需要图引擎。</b>
 *
 * <p><b>它的提示词里没有"对话历史"，只有</b>：
 * 本次任务的 scope + <b>它自己</b>上一轮做过什么（{@code {{history}}}）。
 * <b>这就是上下文隔离。</b>
 *
 * <p>那"看不见"会不会导致 Cognition 说的那种"画风不一致"？
 * <b>会 —— 如果两个子任务实际上互相依赖的话。</b>
 * 所以判据不是"要不要隔离"，而是<b>"这两个子任务需不需要看见彼此才能做对"</b>：
 * 不需要 → 并行 + 隔离是对的；需要 → 它们本来就不该被拆成两个（写 dependsOn 串起来）。
 *
 * <p>★ <b>工具不挂给 AiServices</b>（{@code .tools(...)} 一行都没有）——
 * 挂了之后 LangChain4j 会在本方法内部当场执行工具，而审批是跨 HTTP 请求的异步动作，
 * "工具已经跑了才问要不要批准"没有意义。所以模型只<b>提议</b>，执行由
 * {@code WorkerExecutor} 做。
 */
public interface WorkerAgent {

    @SystemMessage("""
            你是{{role}}。

            ## 你的工作准则
            {{rules}}

            ## 本次任务
            {{task}}

            ## 你可以调用的工具
            {{tools}}

            ## 关于这位用户已知的信息（可能为空）
            {{memory}}

            ## 本轮之前你自己已经做过什么（可能为空）
            {{history}}

            ## 输出要求
            1. 信息够了 → 把结论写进 summary，finish 设为 true。
            2. 还需要查数据 → 把工具名写进 tool、参数写进 toolArgs，finish 设为 false。
            3. **一次只提议一个工具。** 看到它的返回结果后再决定下一步。
            4. 只能从上面的工具清单里选，不要编造工具名。
            5. **不要假装已经执行了** —— 真正的执行由编排器完成。
            6. summary 写**浓缩后的结论**，不要贴工具返回的原文。
               有没查到或不确定的地方，写进 uncovered。
            """)
    // ★ task 同时是 user message：LangChain4j 要求每个方法必须有 @UserMessage，
    //   缺了它启动时不报、调用时才抛 IllegalConfigurationException
    //   （2026-10-14 实测：turn / synthesize 都栽在这一条上）。
    //   写成 "{{task}}" 而不是裸值 —— 参数值要同时填 system 里的 {{task}} 占位符。
    WorkerOutput turn(@V("role") String role,
                      @V("rules") String rules,
                      @UserMessage("{{task}}") String task,
                      @V("tools") String tools,
                      @V("memory") String memory,
                      @V("history") String history);

    /**
     * 一轮的产出。
     *
     * <p>字段刻意做成"两种互斥形态"：<b>要么给结论（finish=true + summary），
     * 要么提议工具（finish=false + tool）</b>。
     * 而不是"一段自由文本 + 一个可选的工具字段" ——
     * 后者没法在编排器里做可靠的分支判断。
     */
    record WorkerOutput(
            @Description("本轮的一句话推理，便于排查。可以很短，也可以留空")
            String thought,

            @Description("信息是否已经足够。够了填 true，还需要调工具填 false")
            boolean finish,

            @Description("结论的浓缩摘要。不要贴工具返回原文。finish 为 false 时可以留空")
            String summary,

            @Description("结论依据的关键位置（文档名 / 表名 / 接口名）。没有就留空数组")
            List<String> evidence,

            @Description("对这个结论的把握，0 到 1 之间的小数")
            double confidence,

            @Description("没有查到或者不确定的部分。没有就留空数组")
            List<String> uncovered,

            @Description("要调用的工具名。不需要调用工具时留空字符串")
            String tool,

            @Description("工具参数，JSON 对象。不需要调用工具时留空对象")
            Map<String, Object> toolArgs
    ) {
    }
}
