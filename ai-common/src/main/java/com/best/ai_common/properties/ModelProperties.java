package com.best.ai_common.properties;

import java.util.Map;
import java.util.Set;

public interface ModelProperties<E extends IEndpoint> {

    String profile();

    Map<String, E> endpoints();

    default E getEndpoint(String profile){
        Map<String, E> endpoints = endpoints();
        if (endpoints == null || endpoints.isEmpty() || !endpoints.containsKey(profile)){
            throw new IllegalArgumentException("未找到制定配置:" + profile + ",已有配置" + (endpoints == null ? Set.of() : endpoints.keySet()));
        }
        return endpoints.get(profile);
    }
}
