package com.best.ai_memory.session.summary_store;

import com.best.ai_memory.session.SessionRedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisSessionSummaryStore implements SessionSummaryStore{

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionSummaryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public String get(Object memoryId) {
        return redis.opsForValue().get(keyOf(memoryId));
    }

    @Override
    public void save(Object memoryId, String summary) {
        if(summary == null || summary.isBlank()){
            delete(memoryId);
            return;
        }
        redis.opsForValue().set(keyOf(memoryId), summary, ttl);
    }

    @Override
    public void delete(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    private String keyOf(Object memoryId){
        return SessionRedisKey.of(SessionRedisKey.SUMMARY_PREFIX, memoryId);
    }
}
