package com.best.ai_common.metrics;

import java.time.Instant;

public record TokenUsageRecord(
        String userId,
        String serviceName,
        String profile,
        String modelName,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Instant occurredAt
) {

    /** 取不到来源服务名时的兜底值 */
    public static final String UNKNOWN_SERVICE = "unknown";
}
