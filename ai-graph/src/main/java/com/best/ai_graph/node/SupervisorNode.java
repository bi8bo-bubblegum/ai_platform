package com.best.ai_graph.node;

import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.graph.RouteDecider;
import com.best.ai_graph.state.MultiAgentState;
import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

@Slf4j
public class SupervisorNode implements NodeAction<MultiAgentState> {

    private final RouteDecider decider;

    private final String fallbackEdgeKey;

    public SupervisorNode(RouteDecider decider, String fallbackEdgeKey) {
        this.decider = decider;
        this.fallbackEdgeKey = fallbackEdgeKey;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        ExpertRouting routing;
        try{
            routing = decider.decide(state.messages());
        } catch (Exception e){
            log.error("调度失败，走兜底节点 {}", fallbackEdgeKey, e);
            return Map.of(
                    MultiAgentState.NEXT, fallbackEdgeKey,
                    MultiAgentState.MESSAGES, AiMessage.from("（调度失败，直接收尾）")
            );
        }
        String edgeKey = routing.edgeKey();
        log.info("调度决定: {} -> {}", routing, edgeKey);
        return Map.of(
                MultiAgentState.NEXT, edgeKey,
                MultiAgentState.MESSAGES, AiMessage.from("(调度：" + routing + ")")
        );
    }
}
