package com.best.assistant_demo.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

@SystemMessage("你是一个智能助手，用友好的语气回答问题")
public interface ChatAssistant {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);

    String ask(@MemoryId String memoryId, @UserMessage String message);

}
