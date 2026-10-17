package com.best.ai_orchestrator_demo.controller;

import com.best.ai_orchestrator.config.OrchestratorProperties;
import com.best.ai_orchestrator.core.RunOutcome;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.orchestrator.Orchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 编排器的 HTTP 入口。
 *
 * <p><b>它很薄</b> —— 只做三件事：把 HTTP 参数翻译成
 * {@link RunState.RunContext}、调编排器、把 {@link RunOutcome} 翻译成 JSON。
 *
 * <p><b>为什么它不保存对话历史</b>：会话历史和长期记忆都由编排器在收敛时自己写。
 * 留给 Controller 写的话，"别忘了存"就是人的纪律 —— 而漏了它的症状
 * （第二轮对话没有上下文）在单轮测试里完全看不出来。
 *
 * <p>⚠️ <b>{@code /approve} 必须带上 {@code runId} 和 {@code taskId}</b>：
 * 前者定位那次运行，后者定位"批的是哪一个提议"。同批里如果有两个子任务
 * 都在等审批，只批一个 —— 一把全批会把第二个也放过去，那是错的。
 */
@RestController
@RequestMapping("/orchestrator")
@RequiredArgsConstructor
@Slf4j
public class OrchestratorController {

    private final Orchestrator orchestrator;
    private final MemoryAccess memory;
    private final RunStateStore store;
    private final OrchestratorProperties props;

    /** 没配 assistant-id 时用它。和自动装配里的取值规则保持一致 */
    @Value("${spring.application.name:orchestrator}")
    private String applicationName;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String user = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        String assistantId = resolveAssistantId();
        String memoryId = MemoryAccess.memoryIdOf(assistantId, user, req.sessionId());

        // 两类记忆都在这里取，取不到都是空串（不抛）—— 记忆是增强项，不是主流程
        RunState.RunContext ctx = new RunState.RunContext(
                user,
                req.sessionId(),
                assistantId,
                memory.longTerm(user, req.message()),
                memory.conversation(memoryId));

        return render(orchestrator.run(req.message(), ctx));
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req) {
        Optional<RunState> found = store.load(req.runId());
        if (found.isEmpty()) {
            return Map.of("status", "error",
                    "message", "找不到运行 " + req.runId() + "（可能已过期、已收敛，或换了实例）");
        }
        log.info("收到审批 runId={} taskId={} approved={}", req.runId(), req.taskId(), req.approved());
        return render(orchestrator.resume(found.get(), req.taskId(), req.approved()));
    }

    private String resolveAssistantId() {
        String configured = props.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }

    /** 把三种结局翻译成 JSON。★ 调用方必须能区分"完成"和"在等人" */
    private Map<String, Object> render(RunOutcome out) {
        if (out instanceof RunOutcome.Completed c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            m.put("runId", c.runId());
            m.put("answer", c.answer());
            m.put("report", c.report());
            return m;
        }
        if (out instanceof RunOutcome.Paused p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "waiting_approval");
            m.put("runId", p.runId());
            m.put("taskId", p.pending().taskId());
            m.put("proposedTool", p.pending().tool());
            m.put("proposedArgs", p.pending().args());
            m.put("risk", p.pending().risk().name());
            m.put("reason", p.pending().reason());
            m.put("report", p.report());
            return m;
        }
        RunOutcome.Failed f = (RunOutcome.Failed) out;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("runId", f.runId());
        m.put("message", f.error());
        m.put("report", f.report());
        return m;
    }

    public record ChatRequest(String message, String sessionId) {
    }

    public record ApproveRequest(String runId, String taskId, boolean approved) {
    }
}
