# 44 · `ai-orchestrator` 拆分：框架零业务 + `ai-orchestrator-demo` 验收台

> **本单修改 43 号的落地形态**：43 号把角色表放在 yml 里、Controller 和启动类住在框架里。
> 本单按裁定把它们拆开 —— **框架只提供机制，业务知识全部搬到 `ai-orchestrator-demo`**。
>
> **代码状态**：5 个模块在临时副本编译通过（`BUILD SUCCESS`），
> 本单代码与源码逐字节一致（脚本拼装 + 反向比对，未手抄）。

---

## 0. 为什么拆（一句话三条）

1. **提示词是代码资产，不是部署配置。** yml 是给端口 / TTL / 阈值 / 开关准备的；
   改一句业务准则应该走 code review、有 diff、可回滚。放 yml 里三条都做不到。
2. **框架认识业务 = 边界失效。** 39 号那条判据的可执行形式是
   `grep -r "com.best.multiagent" ai-graph/src/` 必须 0 行 —— 同一个标准用在这次拆分上：
   **`ai-orchestrator/src/` 里不允许出现任何具体角色**。
3. **`mcp-client-key` 拼错不报错。** yml 里写 `odrer`，要到运行期才表现为"这个角色没工具"；
   搬进代码后由 `DemoRoleConfig` 启动校验（用了的通道必须配过）→ **fail-fast**。

**拆分后的形状**：

```
ai-orchestrator（库 · 25 个文件 · 零业务）
   core / hitl / memory / observability / plan / worker / orchestrator / role / config
   ── 没有 application.yml、没有 Controller、没有启动类、没有 starter-web

ai-orchestrator-demo（应用 · 8 个文件 · 全是业务）
   role/Roles.java + 3 个 rules 实现   ← 业务知识住这里
   config/DemoRoleConfig               ← 唯一一处"把业务接进框架"
   controller + 启动类 + application.yml
```

**依赖方向**：`demo → ai-orchestrator → ai-memory → ai-common`。
框架**从不** import demo 的任何东西（下面的验收有 grep 判据）。

---

## 1. 框架侧改动清单（5 改 1 增 3 删）

| # | 动作 | 文件 |
|---|---|---|
| 1 | **改** | `ai-orchestrator/pom.xml` —— 删 starter-web / mysql-connector-j / maven-plugin |
| 2 | **改** | `config/OrchestratorProperties.java` —— 删 `roles` 字段 |
| 3 | **改** | `config/AiOrchestratorAutoConfiguration.java` —— `agentRoles` 改为兜底空 Map |
| 4 | **改** | `orchestrator/Orchestrator.java` —— 构造器校验 roles 非空（人话报错） |
| 5 | **改** | `worker/WorkerExecutor.java` —— `mcpClientKey == null` 时无工具（纯对话角色） |
| 6 | **增** | `role/RoleRules.java` —— 框架唯一的角色相关件（一个接口） |
| 7 | **删** | `OrchestratorApplication.java`（启动类搬去 demo） |
| 8 | **删** | `controller/OrchestratorController.java`（HTTP 出入口搬去 demo） |
| 9 | **删** | `src/main/resources/application.yml`（配置归应用，不归库） |

**核心变化：roles 的来源从"yml 绑定"变成"业务方注入 Bean"。**
框架的 `agentRoles` bean 加了 `@ConditionalOnMissingBean` —— 业务方提供了就用业务方的，
没人提供时给空 Map + warn，然后由 `Orchestrator` 构造器抛出**带人话的**异常：

```
没有任何角色可用。角色是业务知识，请在业务模块里定义 RoleRules 实现，
并通过 @Bean 提供 Map<String, AgentRole>（key = 角色名）
```

为什么不把异常抛在自动装配类里：那里抛只会得到一句 `Bean creation failed`，
排查时对不上是哪件事。**报错信息的位置和报错本身一样重要。**

---

# 第一部分 · 代码

## 批次 1 · 框架侧改动（6 个文件，给完整内容）

### 文件路径：`ai-orchestrator/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.best</groupId>
        <artifactId>ai_platform</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>ai-orchestrator</artifactId>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- 传递带来 ai-common（模型登记 / MCP 目录 / token 计量） -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-memory</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>

        <!-- 暂停状态要能跨重启、跨实例恢复 → 必须有 Redis 实现。
             声明 optional：没有 Redis 的使用方仍然能编译、能跑（退回内存实现） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- RunEvent 带 Instant 字段，RedisRunStateStore 的专用 mapper 必须注册
             JavaTimeModule 才能序列化它 —— 这是恢复链路的一部分，不是可选的。
             版本由父 pom 的 spring-boot-starter-parent 统一管理，不写死。 -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```
> 删掉的三样各有原因：`starter-web`（HTTP 层搬去 demo，库不需要）、
> `mysql-connector-j`（runtime 依赖是可运行应用的事）、
> `spring-boot-maven-plugin`（库模块不打包可执行 jar）。
> ⚠️ 注意 `spring-boot-starter-data-redis` 仍是 **optional** ——
> 没有 Redis 的使用方退回内存实现，这是 43 号就定好的降级路径。

### 文件路径：`ai-orchestrator/src/main/java/com/best/ai_orchestrator/config/OrchestratorProperties.java`

```java
package com.best.ai_orchestrator.config;

import com.best.ai_orchestrator.core.AgentRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排器的配置项。
 *
 * <p>用属性类而不是散落的 {@code @Value}：默认值写在字段上、
 * 一眼能看全有哪些可调项、从 yml 到对象的路径是显式的。
 */
@Data
@ConfigurationProperties(prefix = "ai.orchestrator")
public class OrchestratorProperties {

    /** 暂停状态的 TTL（小时）。<b>暂停状态不是资产</b> —— 配 TTL 避免无限堆积 */
    private long runTtlHours = 24;

    /**
     * 这张图的标识。<b>它是双重标识，重复会静默串号</b>：
     * ① 暂停状态的 key 命名空间；② 长期记忆表的 {@code source_assistant}。
     *
     * <p>留空则取 {@code spring.application.name} —— 服务名唯一时这项可以不配。
     */
    private String assistantId;

    /** 达到或超过这个危险等级的工具，需要人工批准后才执行。不认识的值按 HIGH 处理 */
    private String approvalMinRisk = "high";

    /** 主循环最大轮数。<b>它是刹车，不是目的地</b> */
    private int maxRounds = 6;

    /** 连续多少轮"已完成子任务数没有增加"就收敛 —— 增量式终止判据 */
    private int noProgressLimit = 2;

    /** 同一批最多并行几个子任务。<b>只影响并行度，不影响正确性</b> */
    private int maxParallel = 3;

    /** 单次运行的 token 预算上限。<b>超了就强制收敛，不是报错</b> */
    private int tokenBudget = 200_000;

    /** 单个子任务的 token 上限 */
    private int perTaskTokenBudget = 30_000;

    /** 默认的工具轮次上限（子任务自己可以覆盖） */
    private int maxToolRounds = 5;

    /** 带进 Planner / Synthesizer 的对话历史最多取多少条 */
    private int conversationTurns = 10;
}
```

> 只删了 `roles` 一个字段（连同 import 里的 `LinkedHashMap` 如果不再使用）。
> 其余参数原样保留 —— 它们是部署参数，本来就该在 yml。

### 文件路径：`ai-orchestrator/src/main/java/com/best/ai_orchestrator/config/AiOrchestratorAutoConfiguration.java`

```java
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
```

> `agentRoles` 从"读 properties 构建"改成"兜底空 Map"。
> ★ 注意 `@ConditionalOnMissingBean` 的位置：它让**业务方的 Bean 优先**，
> 自动装配只负责"没人提供时不至于启动时就缺 Bean"。

### 文件路径：`ai-orchestrator/src/main/java/com/best/ai_orchestrator/orchestrator/Orchestrator.java`

```java
package com.best.ai_orchestrator.orchestrator;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_orchestrator.config.OrchestratorProperties;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.core.Budget;
import com.best.ai_orchestrator.core.Plan;
import com.best.ai_orchestrator.core.RunOutcome;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.core.TaskOutcome;
import com.best.ai_orchestrator.core.TaskSpec;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.observability.RunEvent;
import com.best.ai_orchestrator.plan.Planner;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排器 —— <b>整个模块唯一的控制流</b>。
 *
 * <p><b>它是一段普通 Java 的主循环，不是一张图。</b> 这是本模块最核心的设计决定，
 * 理由是：
 * <ul>
 *   <li>你要的形状是"所有请求相同、只是内容不同" —— 那是<b>循环</b>，不是图。
 *       图引擎真正提供的是"任意拓扑 + 编译期图校验 + 可视化"。</li>
 *   <li>"可中断 / 可暂停 / 可恢复"<b>不需要图引擎</b>：一个可序列化的
 *       {@link RunState} + sealed {@link RunOutcome} 就够。
 *       {@link #run} 和 {@link #resume} 走的是<b>同一个</b> {@link #drive}。</li>
 *   <li>中断 = {@code return}。<b>代码放哪就能停在哪</b> —— 循环内部也行。
 *       如果循环住在图里，中断就只能是节点级的（审批最多拦住第一次，
 *       这是本项目 25 号踩过的坑）。</li>
 * </ul>
 *
 * <p><b>主循环每一步：</b>
 * <pre>
 *   while (true) {
 *       ① 终止判定（全部完成 / 无新进展 / 预算耗尽 / 轮次上限）—— 记录命中哪条
 *       ② 取下一批（依赖已满足的才同批）
 *       ③ 派发执行（顺序或并行；每个子任务有自己的上下文）
 *       ④ 记账 + 事件
 *       ⑤ 若有人要审批 → 存 state → return Paused        ★ 工具没有执行
 *       ⑥ 循环迭代：问规划器"基于已有结论，还要补什么" → plan.add(...)
 *   }
 * </pre>
 *
 * <p>★ <b>{@link RunStateStore} 的存取收在编排器内部</b>（{@code ⑤}），
 * 调用方拿不到"忘了存"的机会 —— 把容易漏的责任从"人的纪律"变成"代码结构"。
 * 这和 33 号把 {@code SessionKey} 收进构造器是同一个手法。
 */
@Slf4j
@Component
public class Orchestrator {

    private final Planner planner;
    private final Synthesizer synthesizer;
    private final TaskDispatcher dispatcher;
    private final TerminationPolicy policy;
    private final RunStateStore store;
    private final MemoryAccess memory;
    private final OrchestratorProperties props;
    private final Map<String, AgentRole> roles;

    public Orchestrator(Planner planner,
                        Synthesizer synthesizer,
                        TaskDispatcher dispatcher,
                        TerminationPolicy policy,
                        RunStateStore store,
                        MemoryAccess memory,
                        OrchestratorProperties props,
                        Map<String, AgentRole> roles) {
        // ★ 角色表为空 = 没人告诉编排器"有哪些专家可用" → 现在就失败。
        // 放到运行期才暴露的话，症状是"规划器随便派个人、然后全都失败"，
        // 排查时要翻到很后面才发现根因是没角色
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何角色可用。角色是业务知识，请在业务模块里定义 RoleRules 实现，"
                            + "并通过 @Bean 提供 Map<String, AgentRole>（key = 角色名）");
        }
        this.planner = planner;
        this.synthesizer = synthesizer;
        this.dispatcher = dispatcher;
        this.policy = policy;
        this.store = store;
        this.memory = memory;
        this.props = props;
        this.roles = roles;
    }

    /**
     * 开始一次运行。
     *
     * @param request 本次用户消息（<b>原始</b>，未消解指代）
     */
    public RunOutcome run(String request, RunState.RunContext ctx) {
        String runId = UUID.randomUUID().toString();
        Budget budget = new Budget(props.getTokenBudget(), props.getPerTaskTokenBudget());
        RunState state = new RunState(runId, request, ctx, budget);

        // ---- 规划 ----
        List<TaskSpec> specs;
        try {
            specs = toSpecs(planner.plan(rolesText(), ctx.conversationText(), request).tasks());
        } catch (Exception e) {
            log.error("规划失败 runId={}", runId, e);
            return new RunOutcome.Failed(runId, "规划失败: " + e.getMessage(), state.report());
        }
        state.setPlan(new Plan(specs.stream().map(TaskNode::new).toList()));
        if (state.getPlan().size() == 0) {
            return new RunOutcome.Failed(runId, "规划器没有产出任何子任务", state.report());
        }
        log.info("运行开始 runId={} 子任务数={}", runId, state.getPlan().size());
        return drive(state);
    }

    /**
     * 恢复一次被审批打断的运行。
     *
     * <p><b>只回答 {@code taskId} 指定的那一个待审批</b>，不是全部。
     * 同批里如果有两个子任务都在等审批，用户需要分别批两次 ——
     * 一把全批会把"第二个"也放过去，那是错误的。
     */
    public RunOutcome resume(RunState state, String taskId, boolean approved) {
        TaskNode node = state.getPlan().getNodes().stream()
                .filter(n -> n.getSpec().id().equals(taskId))
                .findFirst()
                .orElse(null);

        if (node == null || node.getPending() == null) {
            log.warn("resume 指定的 taskId={} 不是待审批状态，忽略", taskId);
        } else if (node.getApproved() != null) {
            log.warn("taskId={} 已经被回答过，忽略重复审批", taskId);
        } else {
            node.decide(approved);
        }
        return drive(state);
    }

    /** ★ 主循环。run 和 resume 都走这里 —— "恢复"就是同一个 while 再转一次 */
    private RunOutcome drive(RunState state) {
        Plan plan = state.getPlan();
        String memoryId = MemoryAccess.memoryIdOf(
                state.getContext().assistantId(), state.getContext().userId(), state.getContext().sessionId());

        try (var ignored = AiUserContext.open(state.getContext().userId())) {
            while (true) {
                // ---- ① 终止判定 ----
                TerminationPolicy.Decision decision = policy.evaluate(plan, state);
                if (decision.stop()) {
                    converge(state, plan, decision.reason());
                    break;
                }

                // ---- ② 取下一批（预算紧张时降并行度） ----
                state.beginRound();
                int parallel = state.getBudget().shouldDowngrade() ? 1 : props.getMaxParallel();
                List<TaskNode> batch = plan.nextBatch(parallel);
                if (batch.isEmpty()) {
                    // 依赖成环，或者没有可执行的。不要死循环
                    converge(state, plan, "没有可执行的子任务（依赖可能成环）");
                    break;
                }
                batch.forEach(n -> state.addEvent(RunEvent.dispatched(n.getSpec().id(), n.getSpec().goal())));

                // ---- ③ 执行 ----
                TaskDispatcher.DispatchResult result = dispatcher.dispatch(batch, state);

                // ---- ④ 记账 + 事件 ----
                for (TaskNode n : batch) {
                    if (n.isDone()) {
                        TaskOutcome o = n.getOutcome();
                        state.getBudget().consume(o.tokensUsed());
                        state.addEvent(RunEvent.completed(o.taskId(),
                                o.isFailed() ? o.error() : o.summary(), o.confidence(), o.tokensUsed()));
                    }
                }

                // ---- ⑤ 有人要审批 → 存 state，return ----
                if (result instanceof TaskDispatcher.DispatchResult.Paused paused) {
                    state.setTerminationReason(null);
                    store.save(state.getRunId(), state);
                    log.info("运行暂停 runId={} 等待审批 task={} tool={}",
                            state.getRunId(), paused.pending().taskId(), paused.pending().tool());
                    return new RunOutcome.Paused(state.getRunId(), paused.pending(), state, state.report());
                }

                // ---- ⑥ 循环迭代 ----
                state.endRound();
                if (!plan.allDone() && !state.getBudget().exhausted()) {
                    refine(state, plan);
                }
            }
        }

        return finish(state, memoryId);
    }

    /**
     * 循环迭代：问规划器"基于已有结论，还要补什么子任务"。
     *
     * <p>★ <b>这是本模块与"并行版 prompt chaining"的分界线。</b>
     * 失败时<b>按"不再追加"处理</b>，不让一次 refine 失败毁掉整轮 ——
     * 已经做完的子任务结果仍然有效，只是少了补洞的机会。
     */
    private void refine(RunState state, Plan plan) {
        try {
            List<TaskSpec> extra = toSpecs(planner.refine(
                    rolesText(),
                    state.getContext().conversationText(),
                    plan.renderOutcomes(),
                    state.getRequest()).tasks());
            plan.add(extra);
        } catch (Exception e) {
            log.warn("循环迭代失败，按不再追加处理。runId={}", state.getRunId(), e);
        }
    }

    private void converge(RunState state, Plan plan, String reason) {
        state.setTerminationReason(reason);
        state.addEvent(RunEvent.converged(reason, plan.doneCount(), plan.size()));
        log.info("运行收敛 runId={} 原因={} 已完成={}/{}",
                state.getRunId(), reason, plan.doneCount(), plan.size());
    }

    /** 汇总 + 触发长期记忆写入 */
    private RunOutcome finish(RunState state, String memoryId) {
        Plan plan = state.getPlan();
        String answer;
        try {
            answer = synthesizer.synthesize(state.getRequest(),
                    state.getContext().conversationText(), plan.renderOutcomes());
        } catch (Exception e) {
            log.error("汇总失败，退化为按子任务罗列。runId={}", state.getRunId(), e);
            // ★ 汇总失败也要给用户一个答案 —— 退化为罗列，不要整个失败
            answer = "（汇总环节出错，以下是各子任务的原始结论）\n" + plan.renderOutcomes();
        }

        // 会话历史 + 长期记忆都由编排器写，不留给调用方 —— "别忘了存"是人的纪律，
        // 收进代码结构才不会漏。漏了的症状（第二轮没有上下文）在单轮测试里完全看不出来
        memory.appendExchange(memoryId, state.getRequest(), answer);
        memory.remember(memoryId, List.of(UserMessage.from(state.getRequest()), AiMessage.from(answer)));
        store.remove(state.getRunId());

        return new RunOutcome.Completed(state.getRunId(), answer, state.report());
    }

    // ---- 小工具 ----

    private List<TaskSpec> toSpecs(List<Planner.PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .map(t -> new TaskSpec(t.id(), t.goal(), t.expectedOutput(), t.boundaries(),
                        t.roleName(), t.dependsOn(), props.getMaxToolRounds()))
                .toList();
    }

    /** 渲染"可用角色"清单，给规划器看 */
    private String rolesText() {
        Map<String, AgentRole> sorted = new LinkedHashMap<>(roles);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, role) ->
                sb.append("- ").append(key).append("：").append(role.description()).append('\n'));
        return sb.toString();
    }
}
```

> 构造器加了 roles 非空校验。为什么放在这里而不是自动装配类里：见第 1 节末。

### 文件路径：`ai-orchestrator/src/main/java/com/best/ai_orchestrator/worker/WorkerExecutor.java`

```java
package com.best.ai_orchestrator.worker;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.core.PendingApproval;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.core.TaskOutcome;
import com.best.ai_orchestrator.core.WorkerTurn;
import com.best.ai_orchestrator.hitl.ApprovalGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行器 —— <b>一个子任务的工具循环就发生在这里</b>。
 *
 * <p>它只做三件事，但三件都必须做对：
 * <ol>
 *   <li>给执行者<b>它自己的</b>上下文（{@code node.getTurns()}），不掺别的</li>
 *   <li>工具<b>要不要先问人</b>由 {@link ApprovalGate} 决定；
 *       需要时<b>立刻返回 {@link AwaitingApproval}、不执行</b> ——
 *       停下那一刻工具真的没被执行，这是审批唯一有意义的形态</li>
 *   <li>循环有上限（{@code spec.maxToolRounds()}）—— 上限是刹车，不是目的地</li>
 * </ol>
 *
 * <p><b>为什么这里能"在循环中间停下"。</b> 因为循环是普通 Java 的 while，
 * 而暂停就是 {@code return}。如果循环住在图里，中断就只能是<b>节点级</b>的 ——
 * 那是本项目 25 号踩过的坑：审批最多拦住第一次。
 */
@Slf4j
public class WorkerExecutor {

    /** 没有提议时执行者写的标记 */
    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 日志里工具结果最多留多少字 */
    private static final int RESULT_LOG_LIMIT = 200;

    private final Map<String, AgentRole> roles;
    private final WorkerAgent worker;
    private final McpClientRegister mcpRegister;
    private final McpToolCatalog toolCatalog;
    private final ApprovalGate gate;

    public WorkerExecutor(Map<String, AgentRole> roles,
                          WorkerAgent worker,
                          McpClientRegister mcpRegister,
                          McpToolCatalog toolCatalog,
                          ApprovalGate gate) {
        this.roles = roles;
        this.worker = worker;
        this.mcpRegister = mcpRegister;
        this.toolCatalog = toolCatalog;
        this.gate = gate;
    }

    /** 一个子任务的执行结果：要么做完，要么卡在审批上 */
    public sealed interface Execution {
        record Finished(TaskOutcome outcome) implements Execution {
        }

        record AwaitingApproval(PendingApproval pending) implements Execution {
        }
    }

    public Execution execute(TaskNode node, RunState state) {
        var spec = node.getSpec();
        AgentRole role = roles.get(spec.roleName());
        if (role == null) {
            // ★ 这是配置错误，不是运行失败 —— 早失败好过跑一半才发现没人接活
            return new Execution.Finished(TaskOutcome.failed(spec.id(),
                    "没有名为 " + spec.roleName() + " 的角色（检查 ai.orchestrator.roles 配置）", 0));
        }

        List<McpTool> tools = (role.mcpClientKey() == null)
                ? List.of()                                   // 纯对话角色：没有工具可调
                : toolCatalog.list(role.mcpClientKey());
        String toolList = renderTools(tools);
        int tokens = 0;
        int maxRounds = spec.maxToolRounds();

        for (int round = 0; round < maxRounds; round++) {
            // ---- A. 结算上一轮留下的审批（如果有） ----
            PendingApproval pending = node.getPending();
            if (pending != null) {
                if (node.getApproved() == null) {
                    // 人还没答 → 原地继续等。★ 这一步让"恢复"天然幂等：
                    // 重复调 resume 不会重复执行工具
                    return new Execution.AwaitingApproval(pending);
                }
                boolean ok = node.getApproved();
                node.clearDecision();
                if (ok) {
                    node.addTurn(WorkerTurn.toolResult(callTool(pending.tool(), pending.args(), role)));
                } else {
                    node.addTurn(WorkerTurn.toolResult("[人工拒绝] 未执行 " + pending.tool()
                            + "。请改用其他方式，或说明你做不到。"));
                }
            }

            // ---- B. 问执行者一轮 ----
            String history = node.renderHistory();
            WorkerAgent.WorkerOutput out;
            try {
                out = worker.turn(role.name(), role.systemPrompt(), spec.render(), toolList,
                        state.getContext().memoryText(), history);
            } catch (Exception e) {
                log.error("子任务 {} 的模型调用失败", spec.id(), e);
                return new Execution.Finished(TaskOutcome.failed(spec.id(),
                        "模型调用失败: " + e.getMessage(), tokens));
            }
            tokens += estimate(history.length() + toolList.length() + spec.render().length(), out);

            if (out.thought() != null && !out.thought().isBlank()) {
                node.addTurn(WorkerTurn.thought(out.thought()));
            }

            // ---- C. 它说做完了 ----
            if (out.finish() || out.tool() == null || out.tool().isBlank()) {
                String summary = out.summary() == null ? "" : out.summary();
                if (summary.isBlank()) {
                    // ★ finish=true 却没给摘要 → 当失败处理，不要静默产出一个空 outcome
                    return new Execution.Finished(TaskOutcome.failed(spec.id(),
                            "执行者标记完成但没有给出结论摘要", tokens));
                }
                return new Execution.Finished(TaskOutcome.done(spec.id(), summary,
                        out.evidence(), out.confidence(), out.uncovered(), tokens));
            }

            // ---- D. 它提议了工具 ----
            String args = toJson(out.toolArgs());
            String toolName = normalizeTool(out.tool(), tools);
            if (toolName == null) {
                // ★ 模型会编工具名。不在清单里就当场写回，让它重选 ——
                //   不要等执行阶段才暴露，那时已经烧掉一次模型调用和一次网络往返
                node.addTurn(WorkerTurn.toolCall(out.tool(), args));
                node.addTurn(WorkerTurn.toolResult("[工具不存在] " + out.tool()
                        + " 不在你的工具清单里，请重新选择。"));
                continue;
            }
            node.addTurn(WorkerTurn.toolCall(toolName, args));

            ToolRisk risk = toolCatalog.riskOf(toolName);
            if (gate.requiresApproval(risk)) {
                PendingApproval p = new PendingApproval(spec.id(), spec.goal(), toolName, args, risk,
                        "工具 " + toolName + " 危险等级为 " + risk + "，需要人工批准");
                node.awaitApproval(p);
                return new Execution.AwaitingApproval(p);   // ★ 工具没有执行
            }

            node.addTurn(WorkerTurn.toolResult(callTool(toolName, args, role)));
        }

        // ---- 循环用尽 ----
        return new Execution.Finished(TaskOutcome.failed(spec.id(),
                "达到工具轮次上限 " + maxRounds + " 轮，未能在限定预算内完成", tokens));
    }

    /**
     * 真正执行 MCP 工具。
     *
     * <p><b>按 {@code role.mcpClientKey()} 选客户端</b> —— 这保证
     * "提示词里看到的工具清单"和"真正能调的工具"是同一个来源，
     * 越权在网关那一层就被拦掉了。
     */
    private String callTool(String tool, String args, AgentRole role) {
        try {
            McpClient client = mcpRegister.get(role.mcpClientKey());
            if (client == null) {
                log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", role.mcpClientKey(), tool);
                return "[工具未能执行] 未配置 " + role.mcpClientKey() + " 的 MCP 通道";
            }
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(args == null || args.isBlank() ? "{}" : args)
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, role.mcpClientKey(), abbreviate(text));
            return "[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text;
        } catch (Exception e) {
            log.error("工具执行异常: tool={} by={} args={}", tool, role.mcpClientKey(), args, e);
            return "[工具执行失败] " + e.getMessage();
        }
    }

    private String renderTools(List<McpTool> tools) {
        if (tools.isEmpty()) {
            return "(本次没有可用工具，如果确实需要执行操作，请说明你缺少什么工具)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            sb.append("- ").append(tool.name())
                    .append(" [危险等级: ").append(tool.risk()).append("]\n")
                    .append("  说明: ").append(safe(tool.description())).append('\n')
                    .append("  参数: ").append(tool.parameters()).append('\n');
        }
        return sb.toString();
    }

    /** 校验模型给的工具名。<b>返回 null 表示不在清单里</b> */
    private String normalizeTool(String proposed, List<McpTool> available) {
        if (proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())) {
            return null;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if (!exists) {
            log.warn("执行者提议了一个不在清单里的工具: [{}]。可用: {}",
                    trimmed, available.stream().map(McpTool::name).toList());
            return null;
        }
        return trimmed;
    }

    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("工具参数序列化失败，按空对象处理: {}", args, e);
            return "{}";
        }
    }

    /**
     * token 估算。
     *
     * <p>★ <b>这是估算，不是精确值。</b> MCP 的 {@code executeTool} 不返回用量，
     * 而 AiServices 也不会把每次调用的 token 交出来。
     * 精确值在 {@code ai_token_usage} 台账里（SDK 层监听器写的）——
     * <b>两者不能混在一张表里</b>，估算只用于"及时刹车"。
     */
    private int estimate(int promptChars, WorkerAgent.WorkerOutput out) {
        int chars = promptChars;
        chars += len(out.thought()) + len(out.summary()) + len(out.tool()) + toJson(out.toolArgs()).length();
        // ★ 用 for 而不是 forEach：lambda 里不能修改捕获的局部变量
        if (out.evidence() != null) {
            for (String s : out.evidence()) {
                chars += len(s);
            }
        }
        if (out.uncovered() != null) {
            for (String s : out.uncovered()) {
                chars += len(s);
            }
        }
        // 粗略：中英混合按 2 字符 ≈ 1 token。宁可高估一点 —— 低估会让预算失灵
        return Math.max(1, chars / 2);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        // ★ 必须 replaceAll：replace 是按字面量替换，"\\s+" 这五个字符根本不存在于文本里
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= RESULT_LOG_LIMIT
                ? oneLine
                : oneLine.substring(0, RESULT_LOG_LIMIT) + "...(截断)";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
```

> 只改了一处：`mcpClientKey == null` 时给空工具清单（纯对话角色）。
> `renderTools` 对空清单本来就有友好文案（"本次没有可用工具"），所以模型会直接给结论。

### 文件路径：`ai-orchestrator/src/main/java/com/best/ai_orchestrator/role/RoleRules.java`

```java
package com.best.ai_orchestrator.role;

/**
 * 一个角色的<b>工作准则</b> —— 填进 {@code WorkerAgent} 提示词骨架的 {@code {{rules}}} 位置。
 *
 * <p><b>为什么准则住在实现类里，不住在配置文件里：</b>
 * <ol>
 *   <li>它是<b>业务知识</b>，不是部署参数。yml 是给"运维可能要改的东西"准备的
 *       （端口、TTL、阈值、开关），改一句提示词应该走 code review、有 diff、可回滚；</li>
 *   <li>yml 的多行文本（{@code |} 块）缩进敏感，改一个字符能破坏整个结构；</li>
 *   <li>{@code mcp-client-key} 这类 key 拼错时 yml <b>不报错</b>，
 *       要到运行期才表现为"这个角色没有工具"。</li>
 * </ol>
 *
 * <p><b>为什么是一个接口 + 每个角色一个实现类，而不是一个大枚举或一个 Map：</b>
 * 准则可以写很长、可以有自己的注释和排版；独立类让"订单准则"和"分析准则"
 * 在文件系统里就是分开的，改一个不会看见另一个。这和 38 号的结论一致。
 *
 * <p>★ <b>只写这个岗位特别注意的地方</b>（4~6 行足够）。
 * 通用输出格式（一次只提议一个工具 / 不要假装已执行 / summary 要浓缩）
 * 由 {@code WorkerAgent} 的提示词骨架保证 —— <b>那是框架契约，写在这里就会漂移</b>。
 */
public interface RoleRules {

    /**
     * 这个角色的工作准则，会原样填进提示词的 {@code {{rules}}} 位置。
     *
     * <p>写"只生成 SELECT，不要写操作"这种<b>判断</b>，
     * 不要写"你是订单助手"（身份由骨架的 {@code {{role}}} 提供）。
     */
    String rules();
}
```

> 框架新增的唯一一个文件。整个框架里跟"角色"相关的就只有这一个接口 ——
> 它的 javadoc 写了三条"为什么准则不住在 yml"，那正是本次拆分的全部理由。

## 批次 2 · demo 模块（8 个文件，全新建）

### 文件路径：`ai-orchestrator-demo/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.best</groupId>
        <artifactId>ai_platform</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>ai-orchestrator-demo</artifactId>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- 编排框架：只提供机制，不认识任何业务 -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-orchestrator</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>

        <!-- HTTP 出入口（框架是库，没有 web；可运行的应用才需要） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- 暂停状态用 Redis（框架里是 optional，这里要真用就自己声明） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- ai-memory 的事实表 + token 台账都在这库 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```
> 注意它声明了框架 optional 掉的三样：starter-web、redis、mysql-connector-j。
> **"库 optional、应用真用"是这个项目反复出现的模式**（ai-common 的 web / mybatis-plus 同款）。

### 文件路径：`ai-orchestrator-demo/src/main/resources/application.yml`

```yaml
server:
  port: 8082

spring:
  application:
    name: ai-orchestrator-demo      # 会成为 assistantId，所以服务名要唯一
  datasource:
    url: jdbc:mysql://localhost:3306/mcp_gateway?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
  data:
    redis:
      host: localhost
      port: 6379

ai:
  orchestrator:
    # assistant-id 可以不配 —— 缺省取 spring.application.name。
    # ★ 它是双重标识（暂停状态命名空间 + 长期记忆 source_assistant），
    #   重复会静默串号，所以服务名一定要唯一。
    assistant-id: ai-orchestrator-demo
    approval-min-risk: high        # 达到或超过这个等级就要人工批准
    max-rounds: 6                  # 主循环轮次上限（刹车，不是目的地）
    no-progress-limit: 2           # 连续几轮没有新完成的子任务就收敛
    max-parallel: 3                # 同一批最多并行几个子任务
    token-budget: 200000           # 单次运行的 token 上限
    per-task-token-budget: 30000   # 单个子任务的 token 上限
    max-tool-rounds: 5             # 单个子任务的工具轮次上限
    conversation-turns: 10         # 带进 Planner / Synthesizer 的对话历史条数
    run-ttl-hours: 24              # 暂停状态的 TTL

    # ★ 角色表不在这里配 —— 它是业务知识，在 role/Roles.java 里定义成枚举。
    #   每个角色一个 RoleRules 实现类（准则），加一个专家 = 加一个枚举值 + 一个类。

  # 向量模型（长期记忆召回要用）。★ 曾经整个 demo 缺这一段 → profile 为 null →
  # EmbeddingModelRegister.getDefault() 对 ConcurrentHashMap.computeIfAbsent(null) → NPE，
  # 启动堆栈最底只有一句没有信息的 "message: null"（43 号从没跑起来过，所以没发现）。
  embed:
    profile: default
    dimensions: 1536
    timeout: 5s
    max-retries: 3
    endpoints:
      default:
        base-url: ${CLOSEAI_BASE_URL}
        api-key: ${CLOSEAI_API_KEY}
        model-name: text-embedding-3-small

  mcp:
    enabled: true
    tool-timeout: 60s
    risk-cache-ttl: 60s
    servers:
      # key = 角色声明的 mcpClientKey；token 决定这个通道能看到哪些工具
      order:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer gw_5vfkI-Qv-xYFQYbxYdTW4b-RM-zb0kmltw4CfTXWrDo"
        timeout: 30s
      analysis:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer gw_Cs9fX_PPVWgqOyn1bz-Ov3sb6xJwKanVRwU09D26Dbw"
        timeout: 30s
      knowledge:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer gw_brYcj6XznQE2DbG5uv0kT-2xYU_OpvfXU9FtiACp1JQ"
        timeout: 30s

  chat:
    profile: free
    temperature: 0.7
    max-tokens: 2000
    timeout: 60s
    endpoints:
      free:
        base-url: ${GATEWAY_BASE_URL}
        api-key: ${GATEWAY_API_KEY}
        model-name: best-1-nothinking

  memory:
    session:
      store: redis
      max-messages: 20
      summary-enabled: true
      summary-ttl: 96h
      ttl: 48h
    user:
      enabled: true
      top-k: 5
      min-score: 0.30
      extract-profile: free
      dedup-threshold: 0.95
      max-facts-per-user: 500
```

> ★ 和 43 号最大的区别：**`ai.orchestrator.roles` 整段消失了**。
> 角色在 `role/Roles.java` 里，这里只剩部署参数。

### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/OrchestratorApplication.java`

```java
package com.best.ai_orchestrator_demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 编排器的示例应用（验收台）。
 *
 * <p>它存在的意义是<b>让框架能被跑起来验证</b>：角色表、HTTP 入口、
 * 配置都在这个模块里；{@code ai-orchestrator} 本身是库，一行业务都不认识。
 *
 * <p>将来第一个真实助手出现时，这个模块的形态就是它的起点 ——
 * 复制它、改 {@code Roles} 枚举和三个 rules 类、改 yml 里的 token。
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/role/OrderRules.java`

```java
package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.role.RoleRules;

/**
 * 订单专家的准则。
 *
 * <p>★ 只写<b>这个岗位特别注意的判断</b>，4~6 行足够。
 * 通用输出格式（一次只提议一个工具 / 不要假装已执行）由框架的提示词骨架保证。
 */
public class OrderRules implements RoleRules {

    @Override
    public String rules() {
        return """
                涉及隐私字段（手机号、证件号、支付交易号）时，只显示后四位，其余用 * 替代。
                金额必须带币种，不要只给数字。
                用户说"我的订单"但没提供订单号时，先询问他最近一笔订单的特征（时间/金额/出发地），
                不要凭空猜一个订单号去查。
                查询结果为空时直接说"没有查到"，不要编造订单。""";
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/role/AnalysisRules.java`

```java
package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.role.RoleRules;

/** 数据分析专家的准则。 */
public class AnalysisRules implements RoleRules {

    @Override
    public String rules() {
        return """
                只生成 SELECT 查询。任何写操作（INSERT / UPDATE / DELETE / DDL）都不要生成 ——
                用户要求修改数据时，说明这里只能查询。
                不要用 SELECT *，只取需要解释的列。
                不知道表结构时，先查看有哪些表和字段，再写查询。
                解释结果时给出数字本身，不要下"业务上说明了什么"的结论 —— 那是用户的判断。
                结果集超过 100 行时说明这一点，不要逐行罗列。""";
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/role/KnowledgeRules.java`

```java
package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.role.RoleRules;

/** 知识库专家的准则。 */
public class KnowledgeRules implements RoleRules {

    @Override
    public String rules() {
        return """
                回答必须基于检索到的文档内容，不要补充自己的判断。
                引用时说明来自哪篇文档，让用户可以自己核对。
                检索不到相关内容时直说"文档里没有"，不要用常识作答 ——
                这个助手的价值就在于"只答文档里有的"。
                检索到的内容相互矛盾时把矛盾指出来，不要替用户选一个。""";
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/role/Roles.java`

```java
package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.role.RoleRules;

/**
 * 这个应用有哪些专家 —— <b>唯一事实来源</b>。
 *
 * <p>三个字段分开，因为它们回答三个不同的问题，合并就是"名字骗人"：
 * <ul>
 *   <li>{@code key} —— <b>规划器选人的键</b>。Planner 输出这个字符串，编排器据此找角色</li>
 *   <li>{@code description} —— 给<b>规划器</b>看的"什么时候派给它"（别人的视角）</li>
 *   <li>{@code rules} —— 给<b>执行者</b>看的"注意什么"（它自己的视角）。
 *       这两者不能复用同一个字符串</li>
 *   <li>{@code mcpClientKey} —— <b>它能看到/能调哪些工具</b>。
 *       = ai.mcp.servers 的 key = 网关分发的 token 授权范围。
 *       越权在网关层就被拦掉，这里不需要再维护一张映射表</li>
 * </ul>
 *
 * <p>★ <b>加一个专家 = 加一个枚举值 + 一个 {@link RoleRules} 实现</b>，
 * 节点、提示词组装、Map 注册全自动 —— 没有"漏改一处静默失效"的空间
 * （这正是 34 号把接线收进循环的原因）。
 */
public enum Roles {

    ORDER("order",
            "订单相关：查订单、订单状态、行程信息、金额、开票情况",
            "order",
            new OrderRules()),

    ANALYSIS("analysis",
            "数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论",
            "analysis",
            new AnalysisRules()),

    KNOWLEDGE("knowledge",
            "知识库相关：检索文档、查资料、找已有方案或说明",
            "knowledge",
            new KnowledgeRules()),

    /**
     * 纯对话专家的样子（示例，默认不启用）：
     * {@code mcpClientKey} 传 {@code null} → 提示词里写"本次没有可用工具"，
     * 模型直接给结论。没有 token 要配，也不走工具审批。
     */
    //    CHAT("chat", "闲聊、澄清需求、解释概念，不需要查任何数据", null, new ChatRules()),
    ;

    private final String key;
    private final String description;
    private final String mcpClientKey;
    private final RoleRules rules;

    Roles(String key, String description, String mcpClientKey, RoleRules rules) {
        this.key = key;
        this.description = description;
        this.mcpClientKey = mcpClientKey;
        this.rules = rules;
    }

    /** 规划器选人的键，也是 {@code Map<String, AgentRole>} 的 key */
    public String key() {
        return key;
    }

    public AgentRole toAgentRole() {
        return new AgentRole(key, description, rules.rules(), mcpClientKey);
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/config/DemoRoleConfig.java`

```java
package com.best.ai_orchestrator_demo.config;

import com.best.ai_common.properties.McpProperties;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator_demo.role.Roles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link Roles} 枚举注册成编排器要的角色表。
 *
 * <p>★ <b>这是整个应用里唯一一处"把业务枚举接进框架"的地方</b>：
 * 框架只认 {@code Map<String, AgentRole>}，不知道 Roles 的存在；
 * 业务也只认 Roles，不碰框架内部。<b>依赖方向是业务 → 框架，不是框架 → 业务。</b>
 *
 * <p><b>为什么启动时校验 MCP 通道存在</b>：角色声明了 {@code mcp-client-key: order}，
 * 而 yml 里没有配 {@code ai.mcp.servers.order} 时 ——
 * 不校验的症状是运行期一条 warn + "这个角色没有工具"，模型开始瞎猜；
 * 校验后是<b>启动失败 + 一句人话</b>。fail-fast 把静默失效变成显式错误。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class DemoRoleConfig {

    @Bean
    public Map<String, AgentRole> agentRoles(McpProperties mcpProperties) {
        Map<String, AgentRole> roles = new LinkedHashMap<>();
        for (Roles r : Roles.values()) {
            roles.put(r.key(), r.toAgentRole());
        }

        // ---- 校验：用到的 MCP 通道必须配过（纯对话角色除外，它没有通道） ----
        for (AgentRole role : roles.values()) {
            String channel = role.mcpClientKey();
            if (channel == null) {
                continue;   // 纯对话角色，没有工具可调
            }
            if (!mcpProperties.getServers().containsKey(channel)) {
                throw new IllegalStateException("角色 " + role.name()
                        + " 声明的 MCP 通道 " + channel + " 没有配置 —— "
                        + "请在 ai.mcp.servers 下补上 " + channel
                        + "（它的值 = 网关分发的 token 授权范围）");
            }
        }

        log.info("角色数={} → {}", roles.size(), roles.keySet());
        return roles;
    }
}
```
### 文件路径：`ai-orchestrator-demo/src/main/java/com/best/ai_orchestrator_demo/controller/OrchestratorController.java`

```java
package com.best.ai_orchestrator_demo.controller;

import com.best.ai_orchestrator.config.OrchestratorProperties;
import com.best.ai_orchestrator.core.RunOutcome;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.hitl.RunStateStore;
import com.best.ai_orchestrator.memory.MemoryAccess;
import com.best.ai_orchestrator.orchestrator.Orchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 编排器的 HTTP 入口。
 *
 * <p><b>它很薄</b> —— 只做三件事：把 HTTP 参数翻译成
 * {@link RunState.RunContext}、调编排器、把 {@link RunOutcome} 翻译成 JSON。
 *
 * <p><b>为什么它不保存对话历史</b>：会话历史和长期记忆都由编排器在收敛时自己写。
 * 留给 Controller 写的话，"别忘了存"就是人的纪律 —— 而漏了它的症状
 * （第二轮对话没有上下文）在单轮测试里完全看不出来。
 *
 * <p>⚠️ <b>{@code /approve} 必须带上 {@code runId} 和 {@code taskId}</b>：
 * 前者定位那次运行，后者定位"批的是哪一个提议"。同批里如果有两个子任务
 * 都在等审批，只批一个 —— 一把全批会把第二个也放过去，那是错的。
 */
@RestController
@RequestMapping("/orchestrator")
@RequiredArgsConstructor
@Slf4j
public class OrchestratorController {

    private final Orchestrator orchestrator;
    private final MemoryAccess memory;
    private final RunStateStore store;
    private final OrchestratorProperties props;

    /** 没配 assistant-id 时用它。和自动装配里的取值规则保持一致 */
    @Value("${spring.application.name:orchestrator}")
    private String applicationName;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String user = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        String assistantId = resolveAssistantId();
        String memoryId = MemoryAccess.memoryIdOf(assistantId, user, req.sessionId());

        // 两类记忆都在这里取，取不到都是空串（不抛）—— 记忆是增强项，不是主流程
        RunState.RunContext ctx = new RunState.RunContext(
                user,
                req.sessionId(),
                assistantId,
                memory.longTerm(user, req.message()),
                memory.conversation(memoryId));

        return render(orchestrator.run(req.message(), ctx));
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req) {
        Optional<RunState> found = store.load(req.runId());
        if (found.isEmpty()) {
            return Map.of("status", "error",
                    "message", "找不到运行 " + req.runId() + "（可能已过期、已收敛，或换了实例）");
        }
        log.info("收到审批 runId={} taskId={} approved={}", req.runId(), req.taskId(), req.approved());
        return render(orchestrator.resume(found.get(), req.taskId(), req.approved()));
    }

    private String resolveAssistantId() {
        String configured = props.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }

    /** 把三种结局翻译成 JSON。★ 调用方必须能区分"完成"和"在等人" */
    private Map<String, Object> render(RunOutcome out) {
        if (out instanceof RunOutcome.Completed c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            m.put("runId", c.runId());
            m.put("answer", c.answer());
            m.put("report", c.report());
            return m;
        }
        if (out instanceof RunOutcome.Paused p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "waiting_approval");
            m.put("runId", p.runId());
            m.put("taskId", p.pending().taskId());
            m.put("proposedTool", p.pending().tool());
            m.put("proposedArgs", p.pending().args());
            m.put("risk", p.pending().risk().name());
            m.put("reason", p.pending().reason());
            m.put("report", p.report());
            return m;
        }
        RunOutcome.Failed f = (RunOutcome.Failed) out;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("runId", f.runId());
        m.put("message", f.error());
        m.put("report", f.report());
        return m;
    }

    public record ChatRequest(String message, String sessionId) {
    }

    public record ApproveRequest(String runId, String taskId, boolean approved) {
    }
}
```

---

# 第二部分 · 检查点与验收

## 3. 检查点命令

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home

# ① 编译（5 个模块）
mvn -o -DskipTests clean compile                              # 期望 BUILD SUCCESS

# ② ★ 框架零业务（39 号判据的本项目版本）—— 必须 0 行
grep -rn "com.best.ai_orchestrator_demo" ai-orchestrator/src/
grep -rn "Roles\b" ai-orchestrator/src/main/java/ | grep -v "RoleRules"

# ③ 框架 pom 无 web
grep -c "starter-web" ai-orchestrator/pom.xml                 # 期望 0

# ④ 启动（demo 模块）
mvn -o -DskipTests -pl ai-orchestrator-demo spring-boot:run
#    日志应出现：角色数=3 → [order, analysis, knowledge]
```

## 4. 验收用例（前两条是本单新增的）

| # | 用例 | 通过标准 | 不过说明什么 |
|---|---|---|---|
| **A1** | **框架零业务** | 第 3 节 ② 两条 grep 全为 0 行 | 拆分没拆干净 |
| **A2** | **忘了提供 roles** | 注释掉 `DemoRoleConfig` 的 `@Bean` → 启动失败，报错含"请在业务模块里定义 RoleRules 实现" | fail-fast 失效，roles 会以难懂的方式失败 |
| A3 | **通道拼错** | 把 `Roles.ANALYSIS` 的 mcpClientKey 改成 `analysi` → **启动失败**，报错列出"通道 analysi 没有配置" | 启动校验失效（这个错误在 yml 时代是静默的） |
| A4 | **纯对话角色** | 取消 `Roles.CHAT` 那行注释 + 加一个 `ChatRules` → 启动成功，问它闲聊能直接答 | null 通道没处理对 |
| A5~A9 | 43 号的用例 2~9（单轮 / 多轮 / 审批 / 连续高危 / 拒绝 / 重启恢复 / 隔离） | 同 43 号 | 回归 |

**A2 和 A3 是本单的存在意义**：它们验的是"错误在启动时就说清楚"，
而 yml 时代的同款错误全是**运行期静默**（角色没工具、拼错 key 不报错）。

## 5. 自查清单

- [ ] `mvn -o -DskipTests clean compile` → BUILD SUCCESS（5 模块）
- [ ] `grep -rn "com.best.ai_orchestrator_demo" ai-orchestrator/src/` → 0 行
- [ ] `grep -c "starter-web" ai-orchestrator/pom.xml` → 0
- [ ] `ai-orchestrator/src/main/resources/` 下**只有** `META-INF/spring/*.imports`（无 application.yml）
- [ ] 启动日志出现 `角色数=3 → [order, analysis, knowledge]`
- [ ] 43 号的 9 个用例全过（尤其第 3 条多轮对话、第 6 条连续两次高危）

---

# 第三部分 · 与 43 号的关系

**43 号文档仍然有效**（12 项能力、5 批敲法、9 个验收用例全部适用），本单只改两件事：

1. **`ai.orchestrator.roles` yml 段作废** —— 若你已按 43 号敲了 yml，删掉那一段
2. **`OrchestratorApplication` / `OrchestratorController` / `application.yml` 从框架搬到 demo** ——
   若你已按 43 号把它们敲在了 `ai-orchestrator` 里，**删掉框架里的那三个，按本单在 demo 里重建**
   （Controller 内容一字不改，只换包名）

**43 号里的 11.1 节（② 档能力无独立出口）不受影响** —— 拆的是"业务/框架边界"，
不是"② / ③ 档边界"，那条的触发条件（第二个非编排使用者）照旧。
