package com.best.ai_common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("ai.chat")
public record ChatModelProperties(
        String profile,
        @DefaultValue("0.7") Double temperature,
        @DefaultValue("2000") Integer maxTokens,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue Map<String, Endpoint> endpoints
) implements ModelProperties<ChatModelProperties.Endpoint> {

    public record Endpoint(
            String baseUrl,
            String apiKey,
            String modelName,
            Double temperature,
            Integer maxTokens,
            Duration timeout
    ) implements IEndpoint{}
}
