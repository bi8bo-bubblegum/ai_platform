package com.best.ai_memory.session.memory_store;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionMemoryStore implements SessionMemoryStore {

    private final Map<Object, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> stored = store.get(memoryId);

        if(stored == null){
            return new ArrayList<>();
        }

        return new ArrayList<>(stored);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId);
    }
}
