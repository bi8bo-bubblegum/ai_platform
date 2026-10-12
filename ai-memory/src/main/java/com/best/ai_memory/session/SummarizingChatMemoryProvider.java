package com.best.ai_memory.session;

import com.best.ai_memory.session.memory_store.SessionMemoryStore;
import com.best.ai_memory.session.summary_store.SessionSummaryStore;
import com.best.ai_memory.session.summarizer.ConversationSummarizer;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;

public class SummarizingChatMemoryProvider implements ChatMemoryProvider {

    private final SessionMemoryStore store;
    private final SessionSummaryStore summaryStore;
    private final ConversationSummarizer summarizer;
    private final int maxMessages;

    public SummarizingChatMemoryProvider(SessionMemoryStore store, SessionSummaryStore summaryStore, ConversationSummarizer summarizer, int maxMessages) {
        this.store = store;
        this.summaryStore = summaryStore;
        this.summarizer = summarizer;
        this.maxMessages = maxMessages;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return new SummarizingChatMemory(memoryId, store, summaryStore, summarizer, maxMessages);
    }
}
