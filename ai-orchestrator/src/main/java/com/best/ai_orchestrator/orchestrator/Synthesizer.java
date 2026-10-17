package com.best.ai_orchestrator.orchestrator;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 汇总器 —— 把若干子任务的结论合成一个给用户的回答。
 *
 * <p><b>它是"错误不静默合并"的最后一道关。</b> 提示词里明确要求：
 * 标注为「未完成」的子任务必须原样告诉用户。
 *
 * <p>没有这一条会发生什么：失败的子任务被跳过，答案看起来完整，
 * 但缺了一块 —— 而且用户完全看不出。这比"明确说这块没查到"糟糕得多。
 *
 * <p>它也看 {@code {{conversation}}}（最近几轮）—— 否则它会给出一个
 * "答非所问"的答案：用户问"那它什么时候发货"，它答"订单 12345 的状态是已签收"。
 */
public interface Synthesizer {

    @SystemMessage("""
            你要把下面几个子任务的结论，合成一个给用户的回答。

            ## 用户最初的问题
            {{request}}

            ## 本次对话最近的内容（用来对齐语气和指代）
            {{conversation}}

            ## 子任务结论
            {{outcomes}}

            ## 要求
            1. 直接回答用户的问题，不要罗列「子任务 1 说了什么」这种过程。
            2. **标注为「未完成」的部分必须原样告诉用户** —— 不要说「我查了」然后跳过它。
               没查到就直说没查到，并说明缺的是什么。
            3. 如果不同子任务的结论互相矛盾，把矛盾指出来，不要替用户选一个。
            4. 不要补充上面材料里没有的信息。
            5. 用中文，简洁。
            """)
    // ★ 同 WorkerAgent.turn：必须有 @UserMessage。user message 用 outcomes
    //   （待合成的材料正文），request / conversation 仍以变量形式进 system
    String synthesize(@V("request") String request,
                      @V("conversation") String conversation,
                      @UserMessage("{{outcomes}}") String outcomes);
}
