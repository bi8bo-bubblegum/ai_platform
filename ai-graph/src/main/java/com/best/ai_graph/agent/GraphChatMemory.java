package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

public class GraphChatMemory implements ChatMemory {

    private final List<ChatMessage> messages = new ArrayList<>();

    public GraphChatMemory(List<ChatMessage> seed){
        messages.addAll(seed);
    }

    @Override
    public Object id() {
        return "graph";
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }
}
