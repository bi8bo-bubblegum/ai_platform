package com.best.ai_graph.graph;

public final class GraphThreadIds {

    private static final String SEP = "::";

    private GraphThreadIds(){}

    public static String of(String sessionId){
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空 —— 它决定这次对话的checkpoint挂在哪个线程下");
        }
        if (sessionId.contains(SEP)) {
            throw new IllegalArgumentException("sessionId 不能包含" + SEP + "，实际：" + sessionId);
        }
        return sessionId;
    }
}
