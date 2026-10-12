package com.best.multiagent.node;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.data.message.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import com.best.multiagent.agent.SupervisorAgent.Routing;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class SupervisorNode implements NodeAction<MultiAgentState> {

    private final SupervisorAgent supervisorAgent;


    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        Routing routing;
        try {
            routing = supervisorAgent.route(ChatMessageTexts.render(state.messages()));
        } catch (Exception e){
            log.error("调度失败，直接收尾", e);
            routing = Routing.FINISH;
        }
        String edgeKey = routing.edgeKey();
        log.info("调度决定: {} -> {}", routing, edgeKey);
        return Map.of(
                MultiAgentState.NEXT, edgeKey,
                MultiAgentState.MESSAGES, AiMessage.from("（调度：" + routing + "）")
        );
    }
}
