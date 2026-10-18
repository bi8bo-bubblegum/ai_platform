package com.best.ai_agentscope.ch03_message_event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class StreamController {

    private final HarnessAgent.Builder agentBuilder;
    private final ObjectMapper objectMapper;

    public StreamController(HarnessAgent.Builder agentBuilder, ObjectMapper objectMapper) {
        this.agentBuilder = agentBuilder;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String sessionId, @RequestParam String message){
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("alice")
                .sessionId(sessionId)
                .build();
        EventAssembler eventAssembler = new EventAssembler();
        return Flux.using(
                agentBuilder::build,
                agent -> agent.streamEvents(new UserMessage(message), ctx),
                HarnessAgent::close
        ).doOnNext(eventAssembler::accept)
                .map(this::toSse)
                .concatWith(Mono.fromCallable(() -> toSse("assembled", eventAssembler.summary())));
    }

    @PostMapping("/msg-anatomy")
    public Map<String, Object> anatomy(@RequestParam String message) {
        try (HarnessAgent agent = agentBuilder.build()) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("alice")
                    .sessionId("anatomy")
                    .build();
            Msg reply = agent.call(new UserMessage(message), ctx).block();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", reply.getId());
            result.put("role", String.valueOf(reply.getRole()));
            result.put("blockTypes", reply.getContent().stream()
                    .map(b -> b.getClass().getSimpleName()).toList());
            result.put("text", reply.getTextContent());
            result.put("generateReason", String.valueOf(reply.getGenerateReason()));
            result.put("timestamp", reply.getTimestamp());
            result.put("usageInputTokens", reply.getUsage() == null ? null : reply.getUsage().getInputTokens());
            result.put("usageOutputTokens", reply.getUsage() == null ? null : reply.getUsage().getOutputTokens());
            return result;
        }
    }

    private ServerSentEvent<String> toSse(AgentEvent event){
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.getType());
        payload.put("id", event.getId());
        if(event.getSource() != null){
            payload.put("source", event.getSource());
        }
        if(event instanceof TextBlockDeltaEvent delta){
            payload.put("delta", delta.getDelta());
        } else if (event instanceof ToolCallStartEvent start){
            payload.put("toolName", start.getToolCallName());
        } else if (event instanceof ToolResultEndEvent end) {
            payload.put("state", String.valueOf(end.getState()));
        }
        return toSse(event.getType().name().toLowerCase(), payload);
    }

    private ServerSentEvent<String> toSse(String name, Object payload){
        try {
            return ServerSentEvent.<String>builder().data(objectMapper.writeValueAsString(payload)).build();
        } catch (Exception e){
            return ServerSentEvent.<String>builder().data("{}").build();
        }
    }
}
