package com.best.ai_memory.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SessionRedisKey {

    public static final String MESSAGE_PREFIX = "ai.mem.session:";

    public static final String SUMMARY_PREFIX = "ai.mem.summary:";

    private SessionRedisKey(){}

    public static String of(String prefix, Object memoryId){
        SessionKey key = SessionKey.parse(String.valueOf(memoryId));
        return prefix + key.assistantId() + ":" + sha256(key.userId()) + ":" + key.sessionId();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256，无法生成安全 key", e);
        }
    }
}
