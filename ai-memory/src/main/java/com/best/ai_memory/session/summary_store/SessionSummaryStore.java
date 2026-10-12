package com.best.ai_memory.session.summary_store;

public interface SessionSummaryStore {

    String get(Object memoryId);

    void save(Object memoryId, String summary);

    void delete(Object memoryId);
}
