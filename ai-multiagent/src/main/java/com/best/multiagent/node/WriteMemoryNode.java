package com.best.multiagent.node;

import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.state.MultiAgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class WriteMemoryNode implements NodeAction<MultiAgentState> {

    private final String assistantId;

    private final UserMemoryRecorder recorder;

    @Override
    public Map<String, Object> apply(MultiAgentState state) throws Exception {
        String userId = state.userId();
        if(userId.isBlank()){
            return Map.of();
        }
        String memoryId = SessionKey.of(assistantId, userId, state.sessionId()).toMemoryId();
        try {
            recorder.recordAsync(memoryId, List.copyOf(state.messages()));
        } catch (Exception e){
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
        return Map.of();
    }
}
