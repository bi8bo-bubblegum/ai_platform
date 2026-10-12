package com.best.ai_memory.session.memory_store;

import com.best.ai_memory.session.SessionRedisKey;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisSessionMemoryStore implements SessionMemoryStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionMemoryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(keyOf(memoryId));
        if(json == null || json.isBlank()){
            return new ArrayList<>();
        }
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if(messages == null || messages.isEmpty()){
            redis.delete(keyOf(memoryId));
            return;
        }
        redis.opsForValue().set(
                keyOf(memoryId),
                ChatMessageSerializer.messagesToJson(messages),
                ttl
        );
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    private String keyOf(Object memoryId){
        return SessionRedisKey.of(SessionRedisKey.MESSAGE_PREFIX, memoryId);
    }
}
