package com.best.ai_common.metrics;

import com.best.ai_common.context.AiUserContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Instant;
import java.util.Map;

public class TokenUsageListener implements ChatModelListener {

    private static final String ATTR_USER_ID = "ai.user.id";

    /** 来源服务名，多服务共用一张表时用于按服务统计 */
    private final String serviceName;

    private final String profile;

    private final String modelName;

    private final TokenUsageRecorder recorder;

    public TokenUsageListener(String serviceName, String profile, String modelName, TokenUsageRecorder recorder) {
        this.serviceName = serviceName;
        this.profile = profile;
        this.modelName = modelName;
        this.recorder = recorder;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        // AiUserContext 是 ThreadLocal，非请求线程（如后台抽取任务）上取不到值会返回 null，
        // 而 attributes 是 ConcurrentHashMap，不允许 null 值，put 会直接抛 NPE。
        // 这里统一兜底成 ANONYMOUS，口径与下方 userIdOf、AiUserContextInterceptor 保持一致。
        String userId = AiUserContext.get();
        attributes.put(ATTR_USER_ID, userId == null || userId.isBlank() ? AiUserContext.ANONYMOUS : userId);
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        TokenUsage tokenUsage = responseContext.chatResponse().tokenUsage();
        if(tokenUsage == null){
            return;
        }
        recorder.record(new TokenUsageRecord(
                userIdOf(responseContext.attributes()),
                serviceName,
                profile,
                modelName,
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount(),
                Instant.now()
        ));
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        recorder.record(new TokenUsageRecord(
                userIdOf(errorContext.attributes()),
                serviceName,
                profile,
                modelName,
                null, null, null,
                Instant.now()
        ));
    }

    private static String userIdOf(Map<Object, Object> attributes){
        Object userId = attributes.get(ATTR_USER_ID);
        return userId instanceof String s && !s.isBlank() ? s : AiUserContext.ANONYMOUS;
    }
}
