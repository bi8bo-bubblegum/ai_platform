package com.best.ai_memory.user;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_common.register.ChatModelRegister;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LlmUserMemoryExtractor implements UserMemoryExtractor{

    public LlmUserMemoryExtractor(ChatModel chatModel) {
        this.assistant = AiServices.builder(ExtractorAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    public record ExtractionResult(List<Fact> memories){
        public record Fact(String content){}
    }

    interface ExtractorAssistant{
        @SystemMessage("""
                你从对话里抽取【关于用户本人的、值得长期记住的】信息。
                
                注意：抽出来的事实是【跨助手共享】的 ——
                用户在客服里说的话，其他助手也会看到。
                所以判断标准里多一条：
                换个场景、换个助手，这句话还成立吗？不成立就别抽

                该抽的：
                - 身份与角色（部门、岗位、职责范围）
                - 稳定的偏好（喜欢什么形式的输出、习惯用什么工具）
                - 明确说过的长期目标、约束、决策
                - 用户自己确认过的事实

                不该抽的：
                - 一次性的问题（"帮我查一下上周的数据"）
                - 只对本次任务成立的上下文（"这次用表格"、"这个订单"、"刚才那个报表"）
                - 助手说的话（只抽用户的信息）
                - 寒暄、客套、情绪
                - 临时的状态（"我现在有点忙"）
                - 从对话里推断出来的、用户没说过的结论

                每条写成一句话，以「用户」开头，不要分点、不要编号。

                示例一
                对话：
                用户：我们部门是做经营分析的，我负责月度报表这块
                助手：好的，那我以后…
                输出：{"memories":[{"content":"用户在经营分析部门，负责月度报表"}]}

                示例二
                对话：
                用户：帮我查一下昨天的 GMV
                助手：昨天 GMV 是…
                输出：{"memories":[]}

                示例三
                对话：
                用户：以后给我结果都直接上表格，别写大段文字
                助手：明白
                输出：{"memories":[{"content":"用户偏好表格形式的输出，不喜欢大段文字"}]}

                如果没有任何值得记的，返回 {"memories":[]}。
                """)
        ExtractionResult extract(@dev.langchain4j.service.UserMessage String conversation);
    }

    private final ExtractorAssistant assistant;

    @Override
    public List<String> extract(List<ChatMessage> recentTurns) {
        if(recentTurns == null || recentTurns.isEmpty()){
            return List.of();
        }
        try {
            ExtractionResult result = assistant.extract(ChatMessageTexts.renderConversation(recentTurns));
            if(result == null || result.memories() == null){
                return List.of();
            }
            return result.memories().stream()
                    .map(ExtractionResult.Fact::content)
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (Exception e){
            log.error("抽取用户事实失败，本次跳过", e);
            return List.of();
        }
    }
}
