package com.best.ai_graph.graph;


import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

@FunctionalInterface
public interface RouteDecider {

    ExpertRouting decide(List<ChatMessage> messages) throws Exception;
}
