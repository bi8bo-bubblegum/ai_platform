package com.best.ai_common.register;

import com.best.ai_common.metrics.TokenUsageListener;
import com.best.ai_common.metrics.TokenUsageRecorder;
import com.best.ai_common.properties.ChatModelProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.List;

public class StreamingChatModelRegister extends ModelRegister<StreamingChatModel> {

    private final ChatModelProperties properties;

    private final String serviceName;

    private final TokenUsageRecorder recorder;

    public StreamingChatModelRegister(ChatModelProperties chatModelProperties, String serviceName, TokenUsageRecorder recorder) {
        this.properties = chatModelProperties;
        this.serviceName = serviceName;
        this.recorder = recorder;
    }


    @Override
    protected StreamingChatModel create(String profile) {
        ChatModelProperties.Endpoint endpoint = properties.getEndpoint(profile);

        List<ChatModelListener> listeners = recorder == null ? List.of() : List.of(new TokenUsageListener(serviceName, profile, endpoint.modelName(), recorder));

        return OpenAiStreamingChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.modelName())
                .temperature(endpoint.temperature() != null ? endpoint.temperature() : properties.temperature())
                .maxTokens(endpoint.maxTokens() != null ? endpoint.maxTokens() : properties.maxTokens())
                .timeout(endpoint.timeout() != null ? endpoint.timeout() : properties.timeout())
                .listeners(listeners)
                .build();
    }

    @Override
    public String defaultProfile() {
        return properties.profile();
    }
}
