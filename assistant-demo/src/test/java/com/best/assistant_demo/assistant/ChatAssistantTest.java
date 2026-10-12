package com.best.assistant_demo.assistant;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ChatAssistantTest {

    @Resource
    private ChatAssistant chatAssistant;

    @Test
    void chat() {
        System.out.println(chatAssistant.chat("u1","我是小明，你记住"));
    }

    @Test
    void ask() {
        System.out.println(chatAssistant.ask("u1","我是谁"));
    }
}