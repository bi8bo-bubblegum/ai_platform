package com.best.assistant_demo.controller;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.assistant_demo.assistant.ChatAssistant;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatStreamController {

    private final ChatAssistant assistant;

    private final UserMemoryRecorder userMemoryRecorder;

    @Value("${ai.memory.assistant-id:${spring.application.name:unknown}}")
    private String assistantId;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message){
        SseEmitter emitter = new SseEmitter(120_000L);
        String userId = AiUserContext.get();
        AtomicBoolean finished = new AtomicBoolean(false);
        String memoryId = SessionKey.of(assistantId, userId, sessionId).toMemoryId();
        assistant.chat(memoryId, message)
                .onPartialResponse(token -> {
                    if(finished.get()){
                        return;
                    }
                    try{
                        emitter.send(token);
                    } catch (IOException | IllegalStateException e){
                        log.warn("推送 token 失败，客户端可能已断开: {}", e.toString());
                        finished.set(true);
                    }
                }).onCompleteResponse(response -> {
                    userMemoryRecorder.recordAsync(
                            memoryId,
                            List.of(UserMessage.from(message),
                                    AiMessage.from(response.aiMessage().text()))
                    );
                    if(finished.compareAndSet(false, true)){
                        emitter.complete();
                    }
                }).onError(error -> {
                    log.error("流式对话失败, memoryId={}", memoryId, error);
                    if(finished.compareAndSet(false, true)){
                        emitter.completeWithError(error);
                    }
                }).start();
        return emitter;
    }
}
