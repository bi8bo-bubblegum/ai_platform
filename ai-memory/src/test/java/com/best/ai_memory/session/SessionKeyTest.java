package com.best.ai_memory.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionKeyTest {

    @Test
    void toMemoryId() {
        System.out.println(SessionKey.parse("u123::456"));
    }

    @Test
    void parse() {
        SessionKey key = new SessionKey("ai-assistant","u123","s456");
        String memoryId = key.toMemoryId();
        System.out.println(memoryId);
    }
}