package com.best.ai_graph.graph;

import org.bsc.langgraph4j.checkpoint.KeyNamingStrategy;

public class NamespacedKeyNamingStrategy implements KeyNamingStrategy {

    private final String prefix;

    public NamespacedKeyNamingStrategy(String namespace) {
        if(namespace == null || namespace.isBlank()){
            throw new IllegalArgumentException("namespace不能为空");
        }
        this.prefix = "langgraph4j:" + namespace + ":";
    }

    @Override
    public String threadKey(String threadId) {
        return prefix + "thread:" + threadId;
    }

    @Override
    public String threadNameKey(String threadName) {
        return prefix + "thread:name:" + threadName + ":active";
    }

    @Override
    public String checkpointKey(String checkpointId) {
        return prefix + "checkpoint:" + checkpointId;
    }

    @Override
    public String checkpointsKey(String threadId) {
        return threadKey(threadId) + ":checkpoints";
    }

    @Override
    public String keyPrefix() {
        return prefix;
    }
}
