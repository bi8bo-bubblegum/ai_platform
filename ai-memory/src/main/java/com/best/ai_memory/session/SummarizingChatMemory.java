package com.best.ai_memory.session;

import com.best.ai_memory.session.memory_store.SessionMemoryStore;
import com.best.ai_memory.session.summary_store.SessionSummaryStore;
import com.best.ai_memory.session.summarizer.ConversationSummarizer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

public class SummarizingChatMemory implements ChatMemory {

    private final Object id;
    private final SessionMemoryStore store;
    private final SessionSummaryStore summaryStore;
    private final ConversationSummarizer summarizer;

    private final int maxMessages;

    public SummarizingChatMemory(Object id, SessionMemoryStore store, SessionSummaryStore summaryStore, ConversationSummarizer summarizer, int maxMessages) {
        if(maxMessages <= 0){
            throw new IllegalArgumentException("maxMessages 必须大于 0");
        }
        this.id = id;
        this.store = store;
        this.summaryStore = summaryStore;
        this.summarizer = summarizer;
        this.maxMessages = maxMessages;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        add(List.of(message));
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        List<ChatMessage> incoming = new ArrayList<>();
        messages.forEach(incoming::add);
        if(incoming.isEmpty()){
            return;
        }
        List<ChatMessage> all = new ArrayList<>(store.getMessages(id));
        all.addAll(incoming);
        int overflow = all.size() - maxMessages;
        if(overflow > 0){
            List<ChatMessage> evicted = new ArrayList<>(all.subList(0, overflow));
            summarizer.append(id, evicted);
            all = new ArrayList<>(all.subList(overflow, all.size()));
        }
        store.updateMessages(id, all);
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        List<ChatMessage> incoming = new ArrayList<>();
        messages.forEach(incoming::add);
        store.updateMessages(id, incoming);
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        String summary = summaryStore.get(id);
        if(summary != null && !summary.isBlank()){
            result.add(SystemMessage.from("[更早的对话摘要]\n" + summary));
        }
        result.addAll(store.getMessages(id));
        return result;
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
        summaryStore.delete(id);
    }
}
