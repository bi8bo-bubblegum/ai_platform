package com.best.ai_common.register;

import com.best.ai_common.properties.EmbeddingModelProperties;
import com.best.ai_common.register.ModelRegister;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

public class EmbeddingModelRegister extends ModelRegister<EmbeddingModel> {

    private final EmbeddingModelProperties properties;

    public EmbeddingModelRegister(EmbeddingModelProperties properties) {
        this.properties = properties;
    }

    @Override
    protected EmbeddingModel create(String profile) {
        EmbeddingModelProperties.Endpoint endpoint = properties.getEndpoint(profile);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.modelName())
                .dimensions(endpoint.dimensions() != null ? endpoint.dimensions() : properties.dimensions())
                .timeout(endpoint.timeout() != null ? endpoint.timeout() : properties.timeout())
                .maxRetries(endpoint.maxRetries() != null ? endpoint.maxRetries() : properties.maxRetries())
                .build();
    }

    @Override
    public String defaultProfile() {
        return properties.profile();
    }
}