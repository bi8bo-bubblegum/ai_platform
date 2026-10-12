package com.best.ai_common.register;

import com.best.ai_common.properties.IEndpoint;
import com.best.ai_common.properties.ModelProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ModelRegister<M> {

    private final Map<String, M> cache = new ConcurrentHashMap<>();

    protected abstract M create(String profile);

    public M get(String profile){
        return cache.computeIfAbsent(profile, this::create);
    }

    public M getDefault(){
        return get(defaultProfile());
    }

    public abstract String defaultProfile();
}
