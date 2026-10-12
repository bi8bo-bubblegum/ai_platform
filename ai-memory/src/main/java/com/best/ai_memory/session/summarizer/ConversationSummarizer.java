package com.best.ai_memory.session.summarizer;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface ConversationSummarizer {

    void append(Object memoryId, List<ChatMessage> evicted);
}
