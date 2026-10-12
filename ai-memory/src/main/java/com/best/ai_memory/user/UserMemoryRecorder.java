package com.best.ai_memory.user;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_memory.config.AiMemoryProperties;
import com.best.ai_memory.session.SessionKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

@Slf4j
public class UserMemoryRecorder {

    private final UserMemoryExtractor extractor;
    private final EmbeddingModel embeddingModel;
    private final UserMemoryStore userMemoryStore;
    private final AiMemoryProperties.User config;
    private final TaskExecutor taskExecutor;

    public UserMemoryRecorder(UserMemoryExtractor extractor, EmbeddingModel embeddingModel, UserMemoryStore userMemoryStore, AiMemoryProperties.User config, TaskExecutor taskExecutor) {
        this.extractor = extractor;
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
        this.config = config;
        this.taskExecutor = taskExecutor;
    }

    public void recordAsync(String memoryId, List<ChatMessage> recentTurns){
        if(!Boolean.TRUE.equals(config.enabled())){
            return;
        }
        if (memoryId == null || memoryId.isBlank() || recentTurns == null || recentTurns.isEmpty()){
            return;
        }
        SessionKey key = SessionKey.parse(memoryId);
        taskExecutor.execute(() -> {
            try (AiUserContext.Scope ignored = AiUserContext.open(key.userId())) {
                record(key.assistantId(), key.userId(), key.sessionId(), recentTurns);
            } catch (Exception e){
                log.error("记录用户记忆失败, userId={}", key.userId(), e);
            }
        });
    }

    public void record(String assistantId, String userId, String sessionId, List<ChatMessage> recentTurns){
        List<String> facts = extractor.extract(recentTurns);
        if(facts.isEmpty()){
            return;
        }

        List<Embedding> embeddings = batchEmbed(facts);
        if(embeddings.size() != facts.size()){
            log.error("向量化返回数量与事实数量不符：{} vs {}，本次跳过", embeddings.size(), facts.size());
            return;
        }
        List<UserMemory> existing = userMemoryStore.findByUserId(userId, config.maxFactsPerUser());
        int inserted = 0;
        for(int i = 0; i < facts.size(); i++){
            String fact = facts.get(i);
            float[] vector = embeddings.get(i).vector();
            if(isDuplicate(vector, existing)){
                log.debug("事实重复,跳过: {}", fact);
                continue;
            }
            UserMemory memory = UserMemory.of(assistantId, userId, fact, sessionId);
            memory.setEmbedding(vector);
            userMemoryStore.insert(memory);
            existing.add(0, memory);
            inserted++;
        }
        if(inserted > 0){
            enforceCapacity(userId);
            log.info("为用户 {} 新增 {} 条长期记忆", userId, inserted);
        }
    }

    private boolean isDuplicate(float[] candidate, List<UserMemory> existing) {
        if (existing.isEmpty()) {
            return false;
        }
        return existing.stream()
                .filter(m -> m.getEmbedding() != null && m.getEmbedding().length > 0)
                .filter(m -> m.getEmbedding().length == candidate.length)
                .anyMatch(m -> CosineSimilarity.between(candidate, m.getEmbedding())
                        >= config.dedupThreshold());
    }

    private float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    private List<Embedding> batchEmbed(List<String> facts){
        try {
            List<TextSegment> segments = facts.stream().map(TextSegment::from).toList();
            return embeddingModel.embedAll(segments).content();
        } catch (Exception e){
            log.error("批量向量化失败，本次跳过 {} 条事实", facts.size(), e);
            return List.of();
        }
    }

    private void enforceCapacity(String userId) {
        int count = userMemoryStore.countByUserId(userId);
        int overflow = count - config.maxFactsPerUser();
        if (overflow > 0) {
            userMemoryStore.deleteOldest(userId, overflow);
            log.warn("用户 {} 的长期记忆已达上限 {}，清理了最老的 {} 条",
                    userId, config.maxFactsPerUser(), overflow);
        }
    }
}
