package com.best.ai_common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("ai.embed")
public record EmbeddingModelProperties(
        String profile,
        @DefaultValue("1536") Integer dimensions,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("3") Integer maxRetries,
        @DefaultValue Map<String, Endpoint> endpoints
) implements ModelProperties<EmbeddingModelProperties.Endpoint> {

    public record Endpoint(
            String baseUrl,
            String apiKey,
            String modelName,
            Integer dimensions,
            Duration timeout,
            Integer maxRetries
    ) implements IEndpoint{}
}
