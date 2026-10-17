package com.best.ai_agentscope.ch01_lifecycle;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final HarnessAgent.Builder agentBuilder;

    public ChatController(HarnessAgent.Builder agentBuilder) {
        this.agentBuilder = agentBuilder;
    }

    @PostMapping("/chat")
    public String chat(@RequestParam String userId, @RequestParam String sessionId, @RequestParam String message){
        try (HarnessAgent agent = agentBuilder.build()){
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build();
            Msg reply = agent.call(new UserMessage(message), ctx).block();
            return reply.getTextContent();
        }
    }
}
