package com.best.multiagent.controller;

import com.best.ai_graph.graph.GraphNodes;
import com.best.ai_graph.graph.GraphThreadIds;
import com.best.ai_graph.state.MultiAgentState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@Slf4j
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final CompiledGraph<MultiAgentState> graph;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req, @RequestHeader(value = "X-User-Id", required = false) String userId){

        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> input = new HashMap<>();
        input.put(MultiAgentState.MESSAGES, List.of(UserMessage.from(req.message())));
        input.put(MultiAgentState.USER_ID, userId == null ? "" : userId);
        input.put(MultiAgentState.SESSION_ID, req.sessionId);
        input.put(MultiAgentState.PROPOSED_TOOL, "");
        input.put(MultiAgentState.APPROVED, false);
        input.put(MultiAgentState.APPROVAL_DECIDED, false);
        return run(() -> graph.invoke(GraphInput.args(input), config), threadId, config);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req){
        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        log.info("收到审批: threadId={} approved={}", threadId, req.approved());
        return run(() -> graph.invoke(GraphInput.resume(Map.of(
                MultiAgentState.APPROVED, req.approved(),
                MultiAgentState.APPROVAL_DECIDED, true)),
                config),
                threadId, config);
    }

    private Map<String, Object> run(GraphRunner runner, String threadId, RunnableConfig config){
        try {
            Optional<MultiAgentState> result = runner.run();
            if(result.isEmpty()){
                return Map.of("status", "error", "message", "图没有返回状态");
            }
            return respond(result.get(), threadId, config);
        } catch (Exception e){
            if (GraphInterruptException.of(e).isPresent()){
                return waiting(threadId, config);
            }
            log.error("图执行失败 threadId={}", threadId, e);
            return Map.of("status", "error", "threadId", threadId, "message", e.getMessage());
        }
    }

    private Map<String, Object> respond(MultiAgentState state, String threadId, RunnableConfig config){
        if(GraphNodes.TOOL_EXECUTE.equals(nextNodeOf(config))){
            return waiting(threadId, config);
        }
        return Map.of(
                "status", "done",
                "threadId", threadId,
                "answer", lastAiText(state.messages())
        );
    }

    private Map<String, Object> waiting(String threadId, RunnableConfig config){
        MultiAgentState state = graph.stateOf(config).map(NodeOutput::state).orElse(null);
        if(state == null){
            return Map.of("status", "waiting_approval", "threadId", threadId);
        }
        return Map.of(
                "status", "waiting_approval",
                "threadId", threadId,
                "proposedTool", state.proposedTool(),
                "proposedArgs", state.proposedArgs()
        );
    }

    private String nextNodeOf(RunnableConfig config){
        return graph.stateOf(config)
                .map(StateSnapshot::nextNodeId)
                .filter(next -> next != null && !next.isBlank())
                .orElse("");
    }

    private String lastAiText(List<ChatMessage> messages){
        for (int i = messages.size() - 1; i >= 0; i--) {
            if(messages.get(i) instanceof AiMessage am){
                String t = am.text();
                if(t != null && !t.isBlank()){
                    return t;
                }
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface GraphRunner{
        Optional<MultiAgentState> run() throws Exception;
    }

    public record ChatRequest(String message, String sessionId){}
    public record ApproveRequest(String sessionId, boolean approved){}
}
