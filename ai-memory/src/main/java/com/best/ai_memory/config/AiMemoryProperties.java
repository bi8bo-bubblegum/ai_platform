package com.best.ai_memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("ai.memory")
public record AiMemoryProperties(
        @DefaultValue Session session,
        @DefaultValue User user
) {

    public record Session(
            @DefaultValue("memory") String store,
            @DefaultValue("20") Integer maxMessages,
            @DefaultValue("true") Boolean summaryEnabled,
            @DefaultValue("48h") Duration ttl,
            @DefaultValue("96h") Duration summaryTtl
            ){}

    public record User(
            @DefaultValue("true") Boolean enabled,
            @DefaultValue("5") Integer topK,
            @DefaultValue("0.60") Double minScore,
            @DefaultValue("free") String extractProfile,
            @DefaultValue("0.95") Double dedupThreshold,
            @DefaultValue("500") Integer maxFactsPerUser
    ){}
}
