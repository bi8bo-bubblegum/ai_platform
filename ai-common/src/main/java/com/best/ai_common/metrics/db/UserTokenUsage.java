package com.best.ai_common.metrics.db;

public record UserTokenUsage(
        String userId,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long requestCount
) {
}
