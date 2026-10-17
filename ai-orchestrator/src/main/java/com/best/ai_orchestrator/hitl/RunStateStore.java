package com.best.ai_orchestrator.hitl;

import com.best.ai_orchestrator.core.RunState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂停中的运行状态存在哪。
 *
 * <p><b>抽成接口的目的，是让"存哪"变成一个部署决定，而不是一个代码决定。</b>
 *
 * <p>⚠️ <b>{@link InMemory} 不能上生产</b>：单机、重启即丢、多实例不共享。
 * 这三条里任何一条都会让"用户批准了但系统找不到那次运行"。
 * 正式部署用 {@link RedisRunStateStore}。
 */
public interface RunStateStore {

    void save(String runId, RunState state);

    Optional<RunState> load(String runId);

    void remove(String runId);

    /**
     * 进程内实现。
     *
     * <p>⚠️ 刻意用 {@code ConcurrentHashMap} 而不是普通 Map ——
     * 多个 HTTP 线程会同时读写，而且"暂停"和"恢复"通常落在不同请求上。
     */
    class InMemory implements RunStateStore {

        private final Map<String, RunState> map = new ConcurrentHashMap<>();

        @Override
        public void save(String runId, RunState state) {
            map.put(runId, state);
        }

        @Override
        public Optional<RunState> load(String runId) {
            return Optional.ofNullable(map.get(runId));
        }

        @Override
        public void remove(String runId) {
            map.remove(runId);
        }
    }
}
