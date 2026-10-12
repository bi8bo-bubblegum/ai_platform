package com.best.ai_common.config;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.db.McpToolMapper;
import com.best.ai_common.metrics.TokenUsageRecord;
import com.best.ai_common.metrics.TokenUsageRecorder;
import com.best.ai_common.metrics.db.AiTokenUsageMapper;
import com.best.ai_common.metrics.db.DbTokenUsageRecorder;
import com.best.ai_common.properties.ChatModelProperties;
import com.best.ai_common.properties.EmbeddingModelProperties;
import com.best.ai_common.properties.McpProperties;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_common.register.EmbeddingModelRegister;
import com.best.ai_common.register.StreamingChatModelRegister;
import dev.langchain4j.mcp.McpToolProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({ChatModelProperties.class, EmbeddingModelProperties.class, McpProperties.class})
public class AiCommonAutoConfiguration {

    // 默认取各服务自身的 spring.application.name，需要时用 ai.metrics.service-name 覆盖
    @Value("${ai.metrics.service-name:${spring.application.name:unknown}}")
    private String serviceName;

    @Bean
    @ConditionalOnProperty(prefix = "ai.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public ChatModelRegister chatModelRegister(
            ChatModelProperties properties,
            ObjectProvider<TokenUsageRecorder> recoderProvider
            ) {
        return new ChatModelRegister(properties, serviceName, recoderProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public StreamingChatModelRegister streamingChatModelRegister(ChatModelProperties properties, TokenUsageRecorder recorder){
        return new StreamingChatModelRegister(properties, serviceName, recorder);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.embed", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public EmbeddingModelRegister embeddingModelRegister(EmbeddingModelProperties properties){
        return new EmbeddingModelRegister(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public AiUserContextInterceptor aiUserContextInterceptor(){
        return new AiUserContextInterceptor();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({BaseMapper.class, DataSource.class})
    @ConditionalOnBean(DataSource.class)
    @MapperScan({"com.best.ai_common.metrics.db","com.best.ai_common.mcp.db"})
    static class DbBackedConfig{
        @Bean
        @ConditionalOnMissingBean
        public TokenUsageRecorder tokenUsageRecorder(AiTokenUsageMapper mapper){
            return new DbTokenUsageRecorder(mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
        public McpToolCatalog mcpToolCatalog(McpClientRegister register, McpToolMapper toolMapper, McpProperties properties){
            return new McpToolCatalog(register, toolMapper, properties.getRiskCacheTtl());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
    static class McpConfig{
        @Bean
        @ConditionalOnMissingBean
        public McpClientRegister mcpClientRegister(McpProperties properties){
            return new McpClientRegister(properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public McpToolProvider mcpToolProvider(McpClientRegister register){
            return McpToolProvider.builder()
                    .mcpClients(register.all())
                    .failIfOneServerFails(false)
                    .build();
        }
    }
}
