package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

public final class SeededAgents {

    private SeededAgents(){}

    public static <T> T build(Class<T> iface, ChatModel chatModel, List<ChatMessage> seed){
        return AiServices.builder(iface)
                .chatModel(chatModel)
                .chatMemory(new GraphChatMemory(seed))
                .build();
    }
}
