package com.best.ai_agentscope.ch02_model;

import com.best.ai_agentscope.model.ModelRegistrar;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.*;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
public class ModelProbeController {

    private final ModelRegistrar registrar;
    private final HarnessAgent.Builder agentBuilder;

    public ModelProbeController(ModelRegistrar registrar) {
        this.registrar = registrar;
        this.agentBuilder = HarnessAgent.builder()
                .name("fault-tolerant")
                .agentId("fault-tolerant")
                .sysPrompt("你是一个用来验证模型容错的助手，回答保持简洁")
                .model("fast")
                .fallbackModel("strong")
                .maxRetries(2)
                .failoverListener((failed, error) -> log.info("[failover]主模型 {} 失败 ({})，切换备用模型", failed.getModelName(),error.getMessage()));
    }

    @GetMapping("/model/capabilities")
    public Map<String, Object> capabilities(){
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : registrar.names()) {
            Model model = registrar.getModel(name);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("modelName", model.getModelName());
            info.put("contextWindowSize", model.getContextWindowSize());
            info.put("nativeStructuredOutput", model.supportsNativeStructuredOutput());
            info.put("nativeStructuredOutputWithTools",
                    model.supportsNativeStructuredOutputWithTools());
            result.put(name, info);
        }
        return result;
    }

    @PostMapping("/model/ask")
    public Map<String, Object> ask(@RequestParam String message){
        Model model = ModelRegistry.resolve("fast");
        StringBuilder text = new StringBuilder();
        List<ChatUsage> usages = new ArrayList<>();
        model.stream(List.of(new UserMessage(message)), List.<ToolSchema>of(), GenerateOptions.builder().build())
                .doOnNext(chunk -> {
                    for(ContentBlock block: chunk.getContent()){
                        if(block instanceof TextBlock tb){
                            text.append(tb.getText());
                        }
                    }
                    if(chunk.getUsage() != null){
                        usages.add(chunk.getUsage());
                    }
                }).blockLast();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text.toString());
        result.put("chunksWithUsage", usages.size());
        if(!usages.isEmpty()){
            ChatUsage last = usages.get(usages.size() - 1);
            result.put("inputTokens", last.getInputTokens());
            result.put("outputTokens", last.getOutputTokens());
            result.put("totalTokens", last.getTotalTokens());
        }
        return result;
    }

    @PostMapping("/model/agent-ask")
    public String agentAsk(@RequestParam String sessionId, @RequestParam String message) {
        try (HarnessAgent agent = agentBuilder.build()) {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("alice")
                    .sessionId(sessionId)
                    .build();
            return agent.call(new UserMessage(message), ctx).block().getTextContent();
        }
    }
}
