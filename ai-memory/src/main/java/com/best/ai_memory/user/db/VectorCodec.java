package com.best.ai_memory.user.db;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class VectorCodec {

    private VectorCodec(){}

    public static byte[] encode(float[] vector){
        if (vector == null){
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    public static float[] decode(byte[] bytes){
        if(bytes == null || bytes.length == 0){
            return new float[0];
        }
        FloatBuffer floatBuffer = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] vector = new float[floatBuffer.remaining()];
        floatBuffer.get(vector);
        return vector;
    }
}
