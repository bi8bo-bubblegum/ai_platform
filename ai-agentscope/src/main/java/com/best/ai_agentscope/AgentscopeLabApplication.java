package com.best.ai_agentscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentscopeLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentscopeLabApplication.class, args);
    }
}
