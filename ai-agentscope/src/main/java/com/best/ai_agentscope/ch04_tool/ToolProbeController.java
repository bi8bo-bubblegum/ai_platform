package com.best.ai_agentscope.ch04_tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
public class ToolProbeController {

    private final Toolkit toolkit;
    private final ObjectMapper objectMapper;

    public ToolProbeController(Toolkit toolkit, ObjectMapper objectMapper) {
        this.toolkit = toolkit;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tool/schemas")
    public Object schemas(){
        return toolkit.getToolSchemas();
    }

    @PostMapping("/tool/call")
    public Map<String, Object> call(@RequestParam String name, @RequestParam String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = objectMapper.readValue(json, Map.class);
        ToolResultBlock r = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id(UUID.randomUUID().toString())
                        .name(name)
                        .input(input)
                        .content(json)
                        .build()).build()).block();
        StringBuilder text = new StringBuilder();
        for (ContentBlock b : r.getOutput()) {
            if(b instanceof TextBlock tb){
                text.append(tb.getText());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", r.getState().name());
        out.put("isSuspended", r.isSuspended());
        out.put("metadata", r.getMetadata());
        out.put("text", text.toString());
        return out;
    }
}
