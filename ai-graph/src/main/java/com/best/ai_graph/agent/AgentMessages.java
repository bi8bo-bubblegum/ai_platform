package com.best.ai_graph.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

public final class AgentMessages {

    private AgentMessages(){}

    public static List<ChatMessage> seed(String systemPrompt, List<ChatMessage> all, int maxHistory){
        List<ChatMessage> seed = new ArrayList<>();
        if(systemPrompt != null && !systemPrompt.isBlank()){
            seed.add(SystemMessage.from(systemPrompt));
        }
        seed.addAll(window(all, maxHistory));
        return seed;
    }

    public static List<ChatMessage> window(List<ChatMessage> all, int maxHistory){
        if(all == null || all.isEmpty()){
            return List.of();
        }
        if(maxHistory <= 0 || all.size() <= maxHistory){
            return List.copyOf(all);
        }
        List<ChatMessage> tail = new ArrayList<>(all.subList(all.size() - maxHistory, all.size()));
        while (!tail.isEmpty() && tail.get(0) instanceof ToolExecutionResultMessage){
            tail.remove(0);
        }
        return tail;
    }
}
