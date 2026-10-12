package com.best.ai_memory.session.summary_store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionSummaryStore implements SessionSummaryStore{

    private final Map<Object, String> store = new ConcurrentHashMap<>();

    @Override
    public String get(Object memoryId) {
        return store.get(memoryId);
    }

    @Override
    public void save(Object memoryId, String summary) {
        store.put(memoryId, summary);
    }

    @Override
    public void delete(Object memoryId) {
        store.remove(memoryId);
    }
}
