package com.best.ai_memory.user;

import com.best.ai_common.register.EmbeddingModelRegister;
import com.best.ai_memory.config.AiMemoryProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
public class MemoryRecallService {

    private final UserMemoryStore store;
    private final EmbeddingModel embeddingModel;
    private final AiMemoryProperties.User config;

    private volatile boolean dimensionMismatchReported = false;

    public MemoryRecallService(UserMemoryStore store, EmbeddingModel embeddingModel, AiMemoryProperties.User config) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.config = config;
    }

    public List<UserMemory> recall(String userId, String query){
        if(userId == null || userId.isBlank() || query == null || query.isBlank()){
            return List.of();
        }
        float[] queryVector;
        try {
            queryVector = embeddingModel.embed(query).content().vector();
        } catch (Exception e){
            log.error("查询向量化失败，本次跳过长期记忆检索, userId={}", userId, e);
            return List.of();
        }
        List<UserMemory> all = store.findByUserId(userId, config.maxFactsPerUser());
        if(all.isEmpty()){
            return List.of();
        }
        return all.stream()
                .filter(m -> isValid(m, queryVector))
                .map(m -> Map.entry(m, CosineSimilarity.between(queryVector, m.getEmbedding())))
                .filter(e -> e.getValue() >= config.minScore())
                .sorted(Comparator.comparingDouble((Map.Entry<UserMemory, Double> e) -> e.getValue()).reversed())
                .limit(config.topK())
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(UserMemory::getCreatedAt).reversed())
                .toList();
    }

    private boolean isValid(UserMemory memory, float[] queryVector) {
        if (memory.getEmbedding() == null || memory.getEmbedding().length == 0) {
            return false;
        }
        if (memory.getEmbedding().length != queryVector.length) {
            // 维度对不上 = 两次用的 embedding 模型不同（多半是 dimensions 被改过）。
            // 这类数据没法比较，直接跳过；但要报出来，否则会表现为
            // "明明库里有几百条，检索永远只返回几条"。
            if (!dimensionMismatchReported) {
                dimensionMismatchReported = true;
                log.error("发现向量维度不一致的记忆（{} vs {}），已跳过。"
                                + "通常是 ai.embed.dimensions 被改过，需要重建历史向量",
                        memory.getEmbedding().length, queryVector.length);
            }
            return false;
        }
        return true;
    }
}
