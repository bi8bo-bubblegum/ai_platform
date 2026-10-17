package com.best.ai_common.register;

import com.best.ai_common.properties.IEndpoint;
import com.best.ai_common.properties.ModelProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ModelRegister<M> {

    private final Map<String, M> cache = new ConcurrentHashMap<>();

    protected abstract M create(String profile);

    public M get(String profile){
        // ★ 先校验，再查缓存。
        // profile 为 null 时 computeIfAbsent 会抛 NPE —— 而 NPE 落在
        // ConcurrentHashMap 内部，报错离原因 5 层远、信息量为零：
        // 2026-10-14 实测，ai-orchestrator-demo 启动时表现为最底一层
        // "Factory method 'memoryRecallService' threw exception with message: null"，
        // 从缺一段 yml 到 NPE 中间隔着 5 层堆栈。
        // 在这里挡下来，说清"缺的是哪个 register 的 profile"。
        // （profile 配了但 endpoints 里没有对应 key 的情况不用管 ——
        //   getEndpoint 已经有人话报错。）
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException(
                    getClass().getSimpleName() + " 的 profile 为空 —— 请检查 ai.*.profile 配置"
                            + "（该配置项未配时它是 null，会以无信息的 NPE 出现在启动堆栈最底）");
        }
        return cache.computeIfAbsent(profile, this::create);
    }

    public M getDefault(){
        return get(defaultProfile());
    }

    public abstract String defaultProfile();
}
