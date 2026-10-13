package com.best.ai_graph.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.graph.MultiAgentGraphFactory;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(GraphProperties.class)
public class AiGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MultiAgentGraphFactory multiAgentGraphFactory(
            ChatModelRegister chatModelRegister,
            McpToolCatalog mcpToolCatalog,
            McpClientRegister mcpClientRegister,
            MemoryRecallService memoryRecallService,
            UserMemoryRecorder userMemoryRecorder,
            RedisProperties redisProperties,
            GraphProperties graphProperties,
            @Value("${spring.application.name:multiagent}") String applicationName
    ){
        return new MultiAgentGraphFactory(
                chatModelRegister,
                mcpToolCatalog,
                mcpClientRegister,
                memoryRecallService,
                userMemoryRecorder,
                redisProperties,
                graphProperties,
                applicationName
        );
    }
}
