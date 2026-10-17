package com.best.ai_agentscope.ch03_message_event;

import io.agentscope.core.event.*;
import io.agentscope.core.message.ToolResultState;

import java.util.LinkedHashMap;
import java.util.Map;

public class EventAssembler {

    private final Map<String, StringBuilder> textByBlock = new LinkedHashMap<>();
    private final Map<String, ToolBuffer> tools = new LinkedHashMap<>();
    private String replyId;
    private int modelCalls = 0;
    private String resultEventText;
    private boolean resultEventSeen = false;

    private void accept(AgentEvent event){
        if(event instanceof AgentStartEvent start){
            this.replyId = start.getReplyId();
        } else if (event instanceof TextBlockDeltaEvent delta){
            textByBlock.computeIfAbsent(delta.getBlockId(), k -> new StringBuilder()).append(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent start) {
            tools.computeIfAbsent(start.getToolCallId(), k -> new ToolBuffer(start.getToolCallName()));
        } else if (event instanceof ToolCallDeltaEvent delta) {
            ToolBuffer toolBuffer = tools.get(delta.getToolCallId());
            if(toolBuffer != null){
                toolBuffer.args.append(delta.getDelta());
            }
        } else if (event instanceof ToolResultEndEvent end) {
            ToolBuffer buffer = tools.get(end.getToolCallId());
            if(buffer != null){
                buffer.state = end.getState();
            }
        } else if (event instanceof ModelCallEndEvent end) {
            modelCalls++;
        } else if (event instanceof AgentResultEvent result){
            resultEventSeen = true;
            resultEventText = result.getResult().getTextContent();
        }
    }

    public Map<String, Object> summary(){
        String reconstructed = text();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replyId", replyId);
        result.put("text", reconstructed);
        result.put("modelCalls", modelCalls);
        result.put("tools", tools.values().stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name,
                        "args", t.args,
                        "state", String.valueOf(t.state)
                )).toList());
        result.put("resultEventSeen", resultEventSeen);
        if(resultEventSeen){
            result.put("resultEventText", resultEventText);
            result.put("matchesResultEvent", reconstructed.equals(resultEventText));
        }
        return result;
    }

    private String text(){
        StringBuilder all = new StringBuilder();
        for (StringBuilder block : textByBlock.values()) {
            if(all.length() >= 0){
                all.append('\n');
            }
            all.append(block);
        }
        return all.toString();
    }

    private static final class ToolBuffer{
        private final String name;
        private final StringBuilder args = new StringBuilder();
        private ToolResultState state;

        private ToolBuffer(String name){
            this.name = name;
        }
    }
}
