package com.best.ai_memory.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_common.register.EmbeddingModelRegister;
import com.best.ai_memory.session.SummarizingChatMemoryProvider;
import com.best.ai_memory.session.memory_store.InMemorySessionMemoryStore;
import com.best.ai_memory.session.memory_store.RedisSessionMemoryStore;
import com.best.ai_memory.session.memory_store.SessionMemoryStore;
import com.best.ai_memory.session.summarizer.ConversationSummarizer;
import com.best.ai_memory.session.summarizer.LlmConversationSummarizer;
import com.best.ai_memory.session.summarizer.NoOpConversationSummarizer;
import com.best.ai_memory.session.summary_store.InMemorySessionSummaryStore;
import com.best.ai_memory.session.summary_store.RedisSessionSummaryStore;
import com.best.ai_memory.session.summary_store.SessionSummaryStore;
import com.best.ai_memory.user.*;
import com.best.ai_memory.user.db.DbUserMemoryStore;
import com.best.ai_memory.user.db.UserMemoryMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AiMemoryProperties.class)
public class AiMemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConversationSummarizer conversationSummarizer(
            AiMemoryProperties properties,
            ChatModelRegister chatModelRegister,
            SessionSummaryStore summaryStore){
        if(!Boolean.TRUE.equals(properties.session().summaryEnabled())){
            return new NoOpConversationSummarizer();
        }

        return new LlmConversationSummarizer(chatModelRegister.getDefault(), summaryStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarizingChatMemoryProvider summarizingChatMemoryProvider(AiMemoryProperties properties, SessionMemoryStore memoryStore,
                                                 SessionSummaryStore summaryStore, ConversationSummarizer summarizer){
        return new SummarizingChatMemoryProvider(memoryStore, summaryStore, summarizer, properties.session().maxMessages());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({BaseMapper.class, DataSource.class})
    @ConditionalOnBean(DataSource.class)
    @MapperScan("com.best.ai_memory.user.db")
    @ConditionalOnProperty(prefix = "ai.memory.user", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class UserMemoryConfig{

        @Bean
        @ConditionalOnMissingBean
        public UserMemoryStore userMemoryStore(UserMemoryMapper userMemoryMapper){
            return new DbUserMemoryStore(userMemoryMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public UserMemoryExtractor userMemoryExtractor(AiMemoryProperties properties, ChatModelRegister register){
            return new LlmUserMemoryExtractor(register.get(properties.user().extractProfile()));
        }

        @Bean
        @ConditionalOnMissingBean
        public MemoryRecallService memoryRecallService(UserMemoryStore store, EmbeddingModelRegister embeddingModelRegister, AiMemoryProperties properties){
            return new MemoryRecallService(store, embeddingModelRegister.getDefault(), properties.user());
        }

        @Bean
        @ConditionalOnMissingBean
        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        public UserMemoryRecorder userMemoryRecorder(UserMemoryExtractor extractor,
                                                     UserMemoryStore store,
                                                     EmbeddingModelRegister register,
                                                     AiMemoryProperties properties,
                                                     TaskExecutor taskExecutor){
            return new UserMemoryRecorder(
                    extractor,
                    register.getDefault(),
                    store,
                    properties.user(),
                    taskExecutor
            );
        }

        @Bean
        @ConditionalOnMissingBean
        public ContentRetriever userMemoryContentRetriever(MemoryRecallService recallService) {
            return new UserMemoryContentRetriever(recallService);
        }

        @Bean
        @ConditionalOnMissingBean
        public RetrievalAugmentor retrievalAugmentor(
                @Qualifier("userMemoryContentRetriever")
                ContentRetriever retriever) {
            // 只配一个 ContentRetriever，其他环节用默认实现。
            // 默认的 queryTransformer 是恒等变换 —— 也就是"不改写用户问题直接检索"。
            // 等你发现"用户问题里有代词，检索不准"时，再来换 CompressingQueryTransformer。
            return DefaultRetrievalAugmentor.builder()
                    .contentRetriever(retriever)
                    .build();
        }
    }


    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ai.memory.session", name = "store", havingValue = "memory", matchIfMissing = true)
    static class InMemoryStoreConfig{
        @Bean
        @ConditionalOnMissingBean
        public SessionMemoryStore sessionMemoryStore(){
            return new InMemorySessionMemoryStore();
        }

        @Bean
        @ConditionalOnMissingBean
        public SessionSummaryStore sessionSummaryStore(){
            return new InMemorySessionSummaryStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "ai.memory.session", name = "store", havingValue = "redis")
    static class RedisStoreConfig{

        @Bean
        @ConditionalOnMissingBean
        public SessionMemoryStore sessionMemoryStore(StringRedisTemplate redis, AiMemoryProperties properties){
            return new RedisSessionMemoryStore(redis, properties.session().ttl());
        }

        @Bean
        @ConditionalOnMissingBean
        public SessionSummaryStore sessionSummaryStore(StringRedisTemplate redis, AiMemoryProperties properties){
            return new RedisSessionSummaryStore(redis, properties.session().summaryTtl());
        }
    }
}
