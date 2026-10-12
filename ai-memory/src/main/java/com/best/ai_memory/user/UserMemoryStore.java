package com.best.ai_memory.user;

import java.util.List;

public interface UserMemoryStore {

    void insert(UserMemory memory);

    List<UserMemory> findByUserId(String userId, int limit);

    int countByUserId(String userId);

    void deleteOldest(String userId, int count);

    void deleteAll(String userId);
}
