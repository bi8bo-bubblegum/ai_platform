package com.best.ai_memory.user;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface UserMemoryExtractor {

    List<String> extract(List<ChatMessage> recentTurns);
}
