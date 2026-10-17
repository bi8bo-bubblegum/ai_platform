package com.best.ai_agentscope.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ModelRegistrar {

    private final Map<String, Model> registered = new LinkedHashMap<>();

    public ModelRegistrar(ModelProperties properties){
        Map<String, ModelProperties.ModelSpec> specs = properties.models();
        if(specs == null || specs.isEmpty()){
            throw new IllegalStateException(
                    "没有任何模型规格 —— 检查 yml 的 ai.models.<name>.* 是否配了");
        }
        specs.forEach((name, spec) -> registered.put(name, build(name, spec)));
        registered.forEach(ModelRegistry::register);
        log.info("[model-registry] 已注册模型: {}",registered.keySet());
    }

    private Model build(String name, ModelProperties.ModelSpec spec){
        require(name, "base-url", spec.baseUrl());
        require(name, "api-key", spec.apiKey());
        require(name, "model-name", spec.modelName());
        return OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .baseUrl(spec.baseUrl())
                .modelName(spec.modelName())
                .generateOptions(GenerateOptions.builder()
                        .temperature(spec.temperature())
                        .maxTokens(spec.maxTokens())
                        .parallelToolCalls(spec.parallelToolCall())
                        .build())
                .build();
    }

    private static void require(String model, String key, String value){
        if(value == null || value.isBlank()){
            throw new IllegalStateException(
                    "模型 " + model + " 缺少 " + key + "（配 ai.models." + model + "." + key + "）");
        }
    }

    public Set<String> names(){
        return registered.keySet();
    }

    public Model getModel(String name){
        return registered.get(name);
    }
}
