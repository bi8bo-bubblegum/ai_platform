package com.best.ai_agentscope.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@ConfigurationProperties(prefix = "ai")
public record ModelProperties(Map<String, ModelSpec> models) {

    public record ModelSpec(String baseUrl,
                            String apiKey,
                            String modelName,
                            @DefaultValue("0.2") Double temperature,
                            @DefaultValue("2048") Integer maxTokens,
                            @DefaultValue("false") Boolean parallelToolCall){}
}
