package com.best.ai_common.register;

import com.best.ai_common.metrics.TokenUsageListener;
import com.best.ai_common.metrics.TokenUsageRecorder;
import com.best.ai_common.properties.ChatModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;

public class ChatModelRegister extends ModelRegister<ChatModel>{

    private final ChatModelProperties properties;

    private final TokenUsageRecorder recorder;

    /** 来源服务名，标记 token 用量属于哪个服务 */
    private final String serviceName;

    public ChatModelRegister(ChatModelProperties properties, String serviceName, TokenUsageRecorder recorder) {
        this.properties = properties;
        this.serviceName = serviceName;
        this.recorder = recorder;
    }

    @Override
    protected ChatModel create(String profile) {
        ChatModelProperties.Endpoint endpoint = properties.getEndpoint(profile);
        List<ChatModelListener> listeners = recorder == null ? List.of()
                : List.of(new TokenUsageListener(serviceName, profile, endpoint.modelName(), recorder));
        return OpenAiChatModel.builder()
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
