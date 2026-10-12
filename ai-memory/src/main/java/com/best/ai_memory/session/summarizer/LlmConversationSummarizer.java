package com.best.ai_memory.session.summarizer;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_memory.session.summary_store.SessionSummaryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LlmConversationSummarizer implements ConversationSummarizer{

    private static final String PROMPT = """
           你是对话摘要器。下面给了【已有摘要】和【新增对话】。
           
           请把两者合并成一段新摘要，规则：
           1. 保留事实：人名、数字、决定、偏好、未完成的事项
           2. 丢弃寒暄、客套、重复内容
           3. 用第三人称陈述，不要写"用户说""助手回答"这类转述
           4. 不要分点，写成一到两段连续的话
           5. 只输出摘要正文，不要标题、不要解释
           6. 总长度不要超过300字，必须控制长度
           
           【已有摘要】
           %s
           
           【新增对话】
           %s
           """;

    private final ChatModel chatModel;
    private final SessionSummaryStore summaryStore;

    public LlmConversationSummarizer(ChatModel chatModel, SessionSummaryStore summaryStore) {
        this.chatModel = chatModel;
        this.summaryStore = summaryStore;
    }

    @Override
    public void append(Object memoryId, List<ChatMessage> evicted) {
        if(evicted == null || evicted.isEmpty()){
            return;
        }
        try {
            String previous = summaryStore.get(memoryId);
            String prompt = PROMPT.formatted(
                    previous == null ? "(无)" : previous,
                    ChatMessageTexts.render(evicted)
            );
            String summary = chatModel.chat(prompt);
            if(summary == null || summary.isBlank()){
                log.warn("摘要在{}上生产了空结果，保留旧摘要", memoryId);
                return;
            }
            summaryStore.save(memoryId, summary.trim());
        } catch (Exception e){
            log.error("生成会话摘要失败，memoryId={}，本条摘要丢失 {} 条消息",
                    memoryId, evicted.size(), e);
        }
    }
}
