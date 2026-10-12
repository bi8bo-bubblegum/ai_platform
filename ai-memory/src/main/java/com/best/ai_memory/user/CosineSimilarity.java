package com.best.ai_memory.user;

public final class CosineSimilarity {

    private CosineSimilarity(){}

    public static double between(float[] a, float[] b){
        if(a == null || b == null || a.length == 0 || b.length == 0){
            return 0.0;
        }
        if(a.length != b.length){
            throw new IllegalArgumentException("向量维度不一致：" + a.length + " vs " + b.length + ",检查 ai.embed.dimensions 是否正确");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if(normA == 0.0 || normB ==0.0){
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
