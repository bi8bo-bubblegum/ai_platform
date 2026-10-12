package com.best.assistant_demo.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_common.register.StreamingChatModelRegister;
import com.best.ai_memory.session.SummarizingChatMemoryProvider;
import com.best.assistant_demo.assistant.ChatAssistant;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AssistantConfig {

    @Bean
    public ChatAssistant chatAssistant(ChatModelRegister chatModelRegister,
                                       StreamingChatModelRegister streamingRegister,
                                       SummarizingChatMemoryProvider summarizingChatMemoryProvider,
                                       McpToolProvider mcpToolProvider,
                                       ObjectProvider<RetrievalAugmentor> augmentorProvider){
        ChatModel chatModel = chatModelRegister.getDefault();
        StreamingChatModel streamingChatModel = streamingRegister.getDefault();
        AiServices<ChatAssistant> builder = AiServices.builder(ChatAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .toolProvider(mcpToolProvider)
                .chatMemoryProvider(summarizingChatMemoryProvider);
        RetrievalAugmentor augmentor = augmentorProvider.getIfAvailable();
        if(augmentor != null){
            builder.retrievalAugmentor(augmentor);
        }
        return builder.build();
    }
}
