package com.best.ai_orchestrator.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.hitl.ApprovalGate;
import com.best.ai_orchestrator.hitl.RedisRunStateStore;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.orchestrator.Orchestrator;
import com.best.ai_orchestrator.orchestrator.Synthesizer;
import com.best.ai_orchestrator.orchestrator.TaskDispatcher;
import com.best.ai_orchestrator.orchestrator.TerminationPolicy;
import com.best.ai_orchestrator.plan.Planner;
import com.best.ai_orchestrator.worker.WorkerAgent;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ai-orchestrator} 的装配。
 *
 * <p><b>为什么走自动装配而不是 {@code @Component} + 组件扫描</b>：
 * 业务模块的 {@code @SpringBootApplication} 只扫描自己的包，
 * 扫不到 {@code com.best.ai_orchestrator}。走
 * {@code META-INF/spring/...AutoConfiguration.imports} 才能让任何依赖它的服务直接拿到 Bean。
 * （和 {@code ai-common} / {@code ai-memory} 的做法一致。）
 *
 * <p>★ <b>依赖都是"必须有"的，不加降级分支</b>：DataSource（MCP 工具目录要读库）、
 * 模型登记、Redis（暂停状态要持久化）。
 * "编排器建不出来"应该表现为<b>启动失败</b>，而不是运行期悄悄不工作。
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class AiOrchestratorAutoConfiguration {

    @Value("${spring.application.name:orchestrator}")
    private String applicationName;

    // ---- 三个 AiService ----
    // ★ 都没有 .tools(...)：工具由 WorkerExecutor 执行，否则审批来不及

    @Bean
    @ConditionalOnMissingBean
    public Planner planner(ChatModelRegister chatModelRegister) {
        return AiServices.builder(Planner.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerAgent workerAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(WorkerAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public Synthesizer synthesizer(ChatModelRegister chatModelRegister) {
        return AiServices.builder(Synthesizer.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    // ---- 角色表 ----

    /**
     * 角色表的<b>兜底</b>：没人提供 {@code Map<String, AgentRole>} Bean 时给一个空的。
     *
     * <p>★ <b>角色不住在配置文件里，也不住在框架里</b> —— 它是<b>业务知识</b>
     * （有哪些专家、各自能干什么、注意什么），由使用方在自己的模块里定义成
     * {@code RoleRules} 实现 + 一个枚举/工厂，然后注入这个 Map。
     * 放 yml 的三个害处：改提示词不走 code review、多行文本编辑体验差、
     * {@code mcp-client-key} 拼错不报错。
     *
     * <p>这里给空 Map 而不是抛异常：**"忘了提供角色"应该由 {@link Orchestrator}
     * 的构造校验报出来**（错误信息更具体），自动装配类里抛异常只会得到一句
     * "Bean creation failed"，排查时对不上是哪件事。
     */
    @Bean
    @ConditionalOnMissingBean
    public Map<String, AgentRole> agentRoles() {
        log.warn("没有任何模块提供角色表（Map<String, AgentRole> Bean）—— "
                + "角色是业务知识，请在业务模块里定义 RoleRules 实现并注册。"
                + "编排器会在启动时因缺少角色而失败。");
        return new LinkedHashMap<>();
    }

    // ---- 记忆 ----

    @Bean
    @ConditionalOnMissingBean
    public MemoryAccess memoryAccess(ChatMemoryProvider chatMemoryProvider,
                                     MemoryRecallService recallService,
                                     UserMemoryRecorder recorder,
                                     OrchestratorProperties props) {
        return new MemoryAccess(chatMemoryProvider, recallService, recorder, props.getConversationTurns());
    }

    // ---- 审批与派发 ----

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGate approvalGate(OrchestratorProperties props) {
        // 不认识的风险等级会按 HIGH 处理（更严格），不会让服务起不来
        return ApprovalGate.threshold(ToolRisk.parse(props.getApprovalMinRisk()));
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerExecutor workerExecutor(Map<String, AgentRole> agentRoles,
                                        WorkerAgent workerAgent,
                                        McpClientRegister mcpClientRegister,
                                        McpToolCatalog mcpToolCatalog,
                                        ApprovalGate approvalGate) {
        return new WorkerExecutor(agentRoles, workerAgent, mcpClientRegister, mcpToolCatalog, approvalGate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskDispatcher taskDispatcher(WorkerExecutor workerExecutor, OrchestratorProperties props) {
        return new TaskDispatcher(workerExecutor, props.getMaxParallel());
    }

    @Bean
    @ConditionalOnMissingBean
    public TerminationPolicy terminationPolicy(OrchestratorProperties props) {
        return new TerminationPolicy(props.getMaxRounds(), props.getNoProgressLimit());
    }

    // ---- 暂停状态存储 ----

    /**
     * 有 Redis 就用 Redis（正式部署）。
     *
     * <p>★ <b>两个条件注解都不能少</b>：
     * {@code @ConditionalOnClass} 保证"classpath 上没有 spring-data-redis 时，
     * 这个 @Bean 方法根本不被解析"（否则会 {@code NoClassDefFoundError}）；
     * {@code @ConditionalOnBean} 保证"有类但没有连接工厂时不创建"。
     * 这是 {@code ai-memory} 里那套范式的用法。
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean
    public RunStateStore redisRunStateStore(StringRedisTemplate redisTemplate,
                                            OrchestratorProperties props) {
        String namespace = resolveAssistantId(props);
        log.info("暂停状态使用 Redis 存储，命名空间={} ttl={}h", namespace, props.getRunTtlHours());
        return new RedisRunStateStore(redisTemplate, namespace,
                Duration.ofHours(props.getRunTtlHours()));
    }

    @Bean
    @ConditionalOnMissingBean(RunStateStore.class)
    public RunStateStore inMemoryRunStateStore() {
        log.warn("未检测到 Redis，暂停状态使用【进程内】存储 —— 单机、重启即丢、多实例不共享。"
                + "正式部署请配置 spring.data.redis.*");
        return new RunStateStore.InMemory();
    }

    // ---- 编排器 ----

    @Bean
    @ConditionalOnMissingBean
    public Orchestrator orchestrator(Planner planner,
                                     Synthesizer synthesizer,
                                     TaskDispatcher taskDispatcher,
                                     TerminationPolicy terminationPolicy,
                                     RunStateStore runStateStore,
                                     MemoryAccess memoryAccess,
                                     OrchestratorProperties props,
                                     Map<String, AgentRole> agentRoles) {
        return new Orchestrator(planner, synthesizer, taskDispatcher, terminationPolicy,
                runStateStore, memoryAccess, props, agentRoles);
    }

    /** 没配 {@code ai.orchestrator.assistant-id} 就用服务名 —— 服务名唯一时这项可以不配 */
    private String resolveAssistantId(OrchestratorProperties props) {
        String configured = props.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }
}
