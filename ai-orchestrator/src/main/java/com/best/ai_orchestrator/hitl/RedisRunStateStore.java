package com.best.ai_orchestrator.hitl;

import com.best.ai_orchestrator.core.RunState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * 暂停状态的 Redis 实现 —— <b>正式部署用这个</b>。
 *
 * <p>它只需要做三件事：序列化、存取、给 TTL。没有别的东西。
 * <b>因为 {@link RunState} 本身刻意做成了"无参构造 + setter + 不引用框架类型"的 POJO</b>，
 * Jackson 能直接序列化/反序列化 —— 不需要为"持久化"改一行编排逻辑。
 *
 * <p>⚠️ <b>给 TTL 的理由</b>：暂停状态不是资产。一个用户点了批准就走的会话，
 * 在 Redis 里躺一周是纯浪费。默认 24 小时，配置项 {@code ai.orchestrator.run-ttl-hours}。
 *
 * <p>★ <b>序列化用自己的 ObjectMapper，不借用 Spring 注入的那个</b> ——
 * "跨请求恢复"是一条独立的序列化契约，它不能被全局配置（其他业务的
 * {@code spring.jackson.*}、Boot 升级后的默认值变化）悄悄改掉。
 * 曾踩过的坑：{@code RunEvent} 带 {@code Instant} 字段，裸 mapper 不注册
 * {@link JavaTimeModule} 会直接抛 {@code InvalidDefinitionException}；
 * 靠"Spring 那个恰好配了"是不够的，这里显式配好。
 */
@Slf4j
public class RedisRunStateStore implements RunStateStore {

    /** ★ 恢复链路的专用 mapper：自己注册模块、自己关特性，不依赖外部配置 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StringRedisTemplate redis;
    private final String namespace;
    private final Duration ttl;

    public RedisRunStateStore(StringRedisTemplate redis,
                              String namespace,
                              Duration ttl) {
        this.redis = redis;
        this.namespace = namespace;
        this.ttl = ttl;
    }

    private String key(String runId) {
        return "ai.orchestrator:run:" + namespace + ":" + runId;
    }

    @Override
    public void save(String runId, RunState state) {
        try {
            redis.opsForValue().set(key(runId), JSON.writeValueAsString(state), ttl);
        } catch (Exception e) {
            // ★ 这条必须抛出去：存失败 = 用户批准后找不到那次运行。
            //   静默吞掉的后果是"审批点完没反应"，而且完全看不出原因。
            log.error("暂停状态写入失败 runId={}", runId, e);
            throw new IllegalStateException("暂停状态写入失败，无法进入等待审批状态", e);
        }
    }

    @Override
    public Optional<RunState> load(String runId) {
        try {
            String json = redis.opsForValue().get(key(runId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(json, RunState.class));
        } catch (Exception e) {
            log.error("暂停状态读取失败 runId={}", runId, e);
            return Optional.empty();
        }
    }

    @Override
    public void remove(String runId) {
        redis.delete(key(runId));
    }
}
