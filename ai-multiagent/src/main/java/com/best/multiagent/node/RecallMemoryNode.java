package com.best.multiagent.node;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemory;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;


@Slf4j
@RequiredArgsConstructor
public class RecallMemoryNode implements NodeAction<MultiAgentState> {

    private final MemoryRecallService memoryRecallService;

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String userId = state.userId();
        String query = ChatMessageTexts.lastUserText(state.messages());
        if(userId.isBlank() || query.isBlank()){
            return Map.of(MultiAgentState.MEMORY_CONTEXT, "");
        }
        List<UserMemory> facts;
        try {
            facts = memoryRecallService.recall(userId, query);
        } catch (Exception e){
            log.warn("长期记忆召回失败，本次跳过。userId={}", userId, e);
            return Map.of(MultiAgentState.MEMORY_CONTEXT, "");
        }
        log.info("长期记忆召回: userId={} 命中={} 条", userId, facts.size());
        return Map.of(MultiAgentState.MEMORY_CONTEXT, render(facts));
    }

    private String render(List<UserMemory> facts){
        if(facts.isEmpty()){
            return "";
        }
        StringBuilder sb = new StringBuilder("[关于这位用户的已知信息，按时间倒序，若冲突以靠前的为准]\n");
        for(UserMemory m : facts){
            sb.append("- ").append(m.getContent()).append('\n');
        }
        return sb.toString();
    }
}
