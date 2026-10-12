# 28 · `ai-graph` 模块定案

> **触发**：用户要求"明确一下到底哪些抽 ai-graph"
>
> **前置**：`27-graph通用件盘点.md`（8 个候选的初步裁定）

---

## 1. 先定准入判据 —— 否则每次都要重新争

`ai-graph` 的定位是**「让 langgraph4j 在这个平台里更好用的一层薄壳」**，不是"图应用的框架"。

要进这个模块，**两条判据必须同时通过**：

| # | 判据 | 为什么 |
|---|---|---|
| **P1** | **它知道具体 state 吗？**（`MultiAgentState` 或任何具体 state 类型） | 知道 → 第二个图的 state 一旦不同，它就得改 → **违反"形状未知就该等"** |
| **P2** | **它定义对外契约吗？**（HTTP 响应格式、错误码、状态枚举） | 定义 → 一旦定就难改，而且契约要跟着业务走 → **不该由通用模块来定** |

**这两条判据的好处是可验证**：抽完之后 `grep -r "MultiAgentState" ai-graph/` 应该是 **0 命中**。

---

## 2. 逐项裁定

| 候选 | P1 知道具体 state？ | P2 定义对外契约？ | 裁定 |
|---|---|---|---|
| `GraphThreadIds` | ❌ 只收字符串 | ❌ | ✅ **进** |
| `NamespacedKeyNamingStrategy` | ❌ 只收 namespace | ❌ | ✅ **进** |
| `GraphCheckpoints`（saver 工厂） | ❌ 只收 `AgentStateFactory<S>` | ❌ | ✅ **进** |
| `CheckpointRedisOptions` | ❌ 纯参数 | ❌ | ✅ **进** |
| `GraphInterrupts`（中断判断） | ❌ 只收 `CompiledGraph<?>` | ❌ | ✅ **进** |
| **两段式 Controller 骨架** | ✅ **读 `state.pendingAction()`** | ✅ **`{status, threadId, pendingAction}`** | ❌ **不进**（两条都违） |
| **记忆节点** `Recall`/`WriteMemoryNode` | ✅ `state.userId()` / `messages()` | ❌ | ❌ 不进 |
| **`SCHEMA` 约定** | ✅ 就是 state 本身 | ❌ | ❌ 不进 |
| `ChatMessageTexts` | ❌ | ❌ | ⚠️ **够格，但不住这儿**（见下） |

### 关于 `ChatMessageTexts` —— 判据通过 ≠ 住在 ai-graph

它也满足 P1 + P2，但它的使用者是 `ai-memory` + `ai-multiagent`。
**而 `ai-memory` 绝不该依赖 `ai-graph`** —— 记忆模块不该被拖上 langgraph4j。

**所以还需要第三条判据，它决定"放哪个公共件"：**

| # | 判据 | 应用 |
|---|---|---|
| **P3** | **放在"最靠近使用者的那个共同上游"** | `ChatMessageTexts` 的使用者横跨 ai-memory 和 ai-multiagent → **共同上游是 `ai-common`** |

**一句话：P1+P2 决定"够不够格做公共件"，P3 决定"放哪个公共件"。**

---

## 3. 模块骨架

```
ai-graph/
├── pom.xml
└── src/main/java/com/best/ai_graph/
    ├── GraphThreadIds.java                    ← 从 ai-multiagent/graph/ 移来
    ├── GraphInterrupts.java                   ← 新增
    └── checkpoint/
        ├── CheckpointRedisOptions.java        ← 新增
        ├── NamespacedKeyNamingStrategy.java   ← 从 ai-multiagent/graph/ 移来
        └── GraphCheckpoints.java              ← 新增
```

**5 个类、2 个包。** 顶部两个是"图的标识与中断"，`checkpoint` 包是"会话状态的持久化"。

**依赖形态：**

```
ai-graph  →  langgraph4j-core
          →  langgraph4j-redis-saver
          →  langgraph4j-langchain4j
          ❌ 不依赖 ai-common
          ❌ 不依赖 ai-memory
          ❌ 不做 Spring 自动装配（只提供静态工具与工厂方法）
```

**三条边界说明：**

1. **不依赖 `ai-common`** —— `ai-common` 至今零 langgraph4j 依赖，这个边界要保持。
   （`ChatMessageTexts` 在 `ai-common` 里，而 `ai-graph` 不需要它。）
2. **不做 `@AutoConfiguration`** —— 这些东西都需要参数（namespace、state factory），
   自动装配会变成"引了就必须配一堆东西"。保持**纯类库**形态最简单。
3. **`langgraph4j-redis-saver` 暂不标 `optional`** —— 标了之后 `GraphCheckpoints` 会
   在类加载时 `NoClassDefFoundError`，得再拆一层。**等真出现"不用 Redis 的图"再拆。**

---

## 4. 完整代码

### 4.1 `com/best/ai_graph/GraphThreadIds.java`

（内容与你现在 `ai-multiagent/graph/GraphThreadIds.java` **完全一致**，只改包名。）

```java
package com.best.ai_graph;

/**
 * threadId 的<b>唯一</b>构造点。
 *
 * <p><b>为什么要收口。</b> 原先 {@code RunnableConfig.builder().threadId(...)} 在 Controller 里
 * 出现两次（{@code /chat} 和 {@code /approve}），各拼一遍：
 * 将来要改格式时必漏一处 —— ai-memory 的 {@code SessionRedisKey} 就是被这个教训逼出来的
 * （两处各写一遍拼接逻辑，摘要那处漏了脱敏，明文 userId 进了 Redis）。
 *
 * <p>收进这里之后，<b>要改只改这一个方法</b>，调用点一行不用动。
 *
 * <p><b>关于命名空间。</b> 现在<b>不</b>往 threadId 里加服务名前缀 ——
 * 那件事由 {@link com.best.ai_graph.checkpoint.NamespacedKeyNamingStrategy}
 * 在 Redis key 层做（更干净）。将来如果出现<b>"一个服务里跑多个图"</b>
 * （那时所有图共用一个 saver、key 前缀不够区分），才需要把图表识加到这里 ——
 * 到那时只改 {@link #of} 的实现即可。
 */
public final class GraphThreadIds {

    private static final String SEP = "::";

    private GraphThreadIds() {}

    /**
     * 由 sessionId 构造 threadId。
     *
     * <p>注意这里<b>刻意不做任何格式加工</b>，只做校验 ——
     * 加工一旦开始就很难回头（存量 checkpoint 会全部失效），留到真需要时再动。
     *
     * @param sessionId 前端传入的会话标识
     * @throws IllegalArgumentException sessionId 为空、或含 {@code ::}
     */
    public static String of(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "sessionId 不能为空 —— 它决定这次对话的 checkpoint 挂在哪个线程下");
        }
        if (sessionId.contains(SEP)) {
            // 现在不含 :: 只是为了给将来"图表识 + ::" 的格式留出无歧义空间
            throw new IllegalArgumentException(
                    "sessionId 不能包含 " + SEP + "，实际: " + sessionId);
        }
        return sessionId;
    }
}
```

### 4.2 `com/best/ai_graph/checkpoint/NamespacedKeyNamingStrategy.java`

（内容与你现在那份**完全一致**，只改包名。）

```java
package com.best.ai_graph.checkpoint;

import org.bsc.langgraph4j.checkpoint.KeyNamingStrategy;

/**
 * 给 checkpoint 的 Redis key 加一层服务命名空间。
 *
 * <p><b>为什么需要它。</b> langgraph4j 默认 key 里<b>没有"是哪个服务 / 哪个图"这个维度</b>：
 * <pre>
 * langgraph4j:thread:{threadId}
 * langgraph4j:thread:name:{threadName}:active
 * langgraph4j:checkpoint:{checkpointId}
 * langgraph4j:thread:{threadId}:checkpoints
 * </pre>
 * 而 {@code RunnableConfig.threadId(x)} 里的 {@code x} 其实被当作 <b>threadName</b> 用 ——
 * 框架拿它查 bucket 取出内部 UUID 才是真正的存储标识。所以两个服务只要用了相同的
 * threadName（都叫 {@code "s1"}），就会取到同一个内部 threadId、<b>共用 checkpoint 空间</b>。
 *
 * <p>格式<b>照抄</b> {@code DefaultKeyNamingStrategy}，只把 {@code langgraph4j:}
 * 换成 {@code langgraph4j:<namespace>:} —— 形状不变、只多一层前缀，风险最低。
 */
public class NamespacedKeyNamingStrategy implements KeyNamingStrategy {

    private final String prefix;

    public NamespacedKeyNamingStrategy(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            // 不允许多一个空命名空间 —— 那等于前缀是 "langgraph4j::"，
            // 看似隔离了其实没有，而且比默认 key 更难排查
            throw new IllegalArgumentException("namespace 不能为空");
        }
        this.prefix = "langgraph4j:" + namespace + ":";
    }

    @Override
    public String threadKey(String threadId) {
        return prefix + "thread:" + threadId;
    }

    @Override
    public String threadNameKey(String threadName) {
        return prefix + "thread:name:" + threadName + ":active";
    }

    @Override
    public String checkpointKey(String checkpointId) {
        return prefix + "checkpoint:" + checkpointId;
    }

    @Override
    public String checkpointsKey(String threadId) {
        return threadKey(threadId) + ":checkpoints";
    }

    /**
     * 清理时用的前缀。
     *
     * <p><b>必须和上面几个方法用的是同一个前缀</b>，否则 {@code RedisSaver.cleanupAll()}
     * 会漏掉一部分 key —— 那是个"看起来清理成功、其实留了垃圾"的静默故障。
     */
    @Override
    public String keyPrefix() {
        return prefix;
    }
}
```

### 4.3 `com/best/ai_graph/checkpoint/CheckpointRedisOptions.java`（新增）

```java
package com.best.ai_graph.checkpoint;

import java.time.Duration;

/**
 * checkpoint 存储的 Redis 连接参数。
 *
 * <p>为什么不直接用 Spring Boot 的 {@code RedisProperties}：
 * 那会把 {@code spring-boot-autoconfigure} 拖进 {@code ai-graph}，
 * 而这个模块刻意保持"只依赖 langgraph4j"的纯类库形态。
 * 一个 4 字段的 record 成本更低、边界更清楚。
 *
 * @param host     Redis 主机
 * @param port     端口
 * @param password 密码。允许为空 —— 本地 Redis 常常没密码
 * @param ttl      checkpoint 的存活时间。<b>会话状态不是资产</b>，配 TTL 避免无限堆积
 */
public record CheckpointRedisOptions(String host, int port, String password, Duration ttl) {

    /** 紧凑构造器：把校验从"调用方的纪律"变成"构造时就炸" */
    public CheckpointRedisOptions {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Redis host 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Redis port 非法: " + port);
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正 —— 会话状态不该永久保留");
        }
    }

    /** 无密码的便捷构造 */
    public CheckpointRedisOptions(String host, int port, Duration ttl) {
        this(host, port, null, ttl);
    }
}
```

### 4.4 `com/best/ai_graph/checkpoint/GraphCheckpoints.java`（新增）

```java
package com.best.ai_graph.checkpoint;

import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.util.concurrent.TimeUnit;

/**
 * checkpoint saver 的构造入口。
 *
 * <p><b>它存在的核心理由不是"少写几行"，而是"漏不了"。</b>
 * 直接 {@code RedisSaver.builder()} 的话，有两件事全靠调用方记得：
 * <ol>
 *   <li>配 {@link NamespacedKeyNamingStrategy} —— <b>漏了就串号</b>，而且不报错</li>
 *   <li>传 {@code stateSerializer} —— ChatMessage 用 Java 原生序列化有问题，
 *       漏了存消息会失败</li>
 * </ol>
 * 收进工厂方法之后，这两样都<b>没有默认值可漏</b>：namespace 是必填参数，
 * serializer 由 {@code stateFactory} 内部构造。
 *
 * <p><b>它不知道任何具体的 state 类型</b> —— 只通过 {@link AgentStateFactory} 工作，
 * 所以对任意图都适用。
 */
public final class GraphCheckpoints {

    private GraphCheckpoints() {}

    /**
     * 造一个带命名空间的 RedisSaver。
     *
     * @param options      Redis 连接参数
     * @param stateFactory 具体 state 的构造器，例如 {@code MultiAgentState::new}
     * @param namespace    服务 / 图的命名空间，建议直接用 {@code spring.application.name}
     * @throws IllegalArgumentException namespace 为空时
     */
    public static <S extends AgentState> RedisSaver redisSaver(
            CheckpointRedisOptions options,
            AgentStateFactory<S> stateFactory,
            String namespace) {

        var builder = RedisSaver.builder()
                .host(options.host())
                .port(options.port())
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(namespace))
                .ttl(options.ttl().toMinutes(), TimeUnit.MINUTES)
                // LC4jStateSerializer 内部注册了一整套 ChatMessage 序列化器 ——
                // 不传它，存消息会失败（这是个"启动正常、一存就炸"的坑）
                .stateSerializer(new LC4jStateSerializer<>(stateFactory));

        // password 只在非空时设置 —— 空串传给 builder 可能出问题。
        // 这个判断原先在每个调用点都要写一遍，现在内化在这里。
        if (options.password() != null && !options.password().isBlank()) {
            builder.password(options.password());
        }
        return builder.build();
    }
}
```

### 4.5 `com/best/ai_graph/GraphInterrupts.java`（新增）

```java
package com.best.ai_graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInterruptException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;

/**
 * 「图是不是停在中断点」的判断工具。
 *
 * <p><b>为什么不抽整个 Controller 骨架，只抽这几个判断。</b>
 * 两段式 Controller 里真正通用的只有"判断"这一半：
 * 是不是中断、下一个节点是谁、是不是停在某个闸门。
 * 另一半（把结果翻译成 HTTP 响应体）依赖具体 state 的字段（如 {@code pendingAction}），
 * 而且它是对外 API 契约 —— 那两样都该留在业务侧。
 *
 * <p><b>这套判断为什么值得抽。</b> {@link #nextNodeId} 里那句
 * {@code getState(config)} <b>可能返回 null</b>，漏判就 NPE；而它只在特定时序下触发。
 * 收进一次调用之后，"记得判 null"这件事就不用每个图各记一遍了。
 */
public final class GraphInterrupts {

    private GraphInterrupts() {}

    /**
     * 这次异常是不是"图停在中断点"，而不是真的出错。
     *
     * <p>注意 langgraph4j 到底是抛异常还是正常返回，两种实现都要能兼容 ——
     * 所以判断方式是"catch 住任何异常，再问一句是不是中断"。
     */
    public static boolean isInterrupt(Throwable e) {
        return GraphInterruptException.of(e).isPresent();
    }

    /**
     * 下一个要执行的节点名。
     * 停在中断点时为闸门节点名；正常跑完或查不到快照时返回空串。
     */
    public static String nextNodeId(CompiledGraph<?> graph, RunnableConfig config) {
        StateSnapshot<?> snapshot = graph.getState(config);
        if (snapshot == null) {
            return "";
        }
        String next = snapshot.nextNodeId();
        return next == null ? "" : next;
    }

    /**
     * 是不是正停在指定的闸门节点上。
     *
     * <p>这是本类最主要的收益 —— 把
     * {@code gate.equals(graph.getState(cfg).nextNodeId())} 这一串
     * （含 null 判定的细节）收成一次调用。
     */
    public static boolean waitingAt(CompiledGraph<?> graph, RunnableConfig config, String gateNode) {
        return gateNode != null && gateNode.equals(nextNodeId(graph, config));
    }
}
```

---

## 5. `ai-graph/pom.xml`

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

    <artifactId>ai-graph</artifactId>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.bsc.langgraph4j</groupId>
                <artifactId>langgraph4j-bom</artifactId>
                <version>${langgraph4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-redis-saver</artifactId>
        </dependency>
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-langchain4j</artifactId>
        </dependency>
    </dependencies>
</project>
```

**注意没有任何 `com.best` 依赖** —— 这就是 P1 判据的物理体现。

---

## 6. 迁移步骤

| # | 动作 | 说明 |
|---|---|---|
| 1 | 父 pom `<modules>` 加 `<module>ai-graph</module>` | 位置放在 `ai-memory` 之后 |
| 2 | 新建 `ai-graph`（pom + 5 个类） | 见第 4、5 节 |
| 3 | **删** `ai-multiagent/.../graph/` 整个包 | 两个类已移走，旧位置不能留副本 |
| 4 | 改 `ai-multiagent/pom.xml` | 加 `ai-graph` 依赖；**保留** langgraph4j 三件套（因为 ai-multiagent 直接 import 了 `CompiledGraph` 等） |
| 5 | 改 `MultiAgentGraphConfig` | saver 那 10 行 → `GraphCheckpoints.redisSaver(...)`；改 import |
| 6 | 改 `AgentController` | 用 `GraphInterrupts` / `GraphThreadIds`；改 import |
| 7 | **接线**（见下） | 这才是让它真正生效的一步 |

### 6.1 `MultiAgentGraphConfig` 的 saver 段改成

```java
        var saver = GraphCheckpoints.redisSaver(
                new CheckpointRedisOptions(redisHost, redisPort, redisPassword,
                        Duration.ofHours(ttlHours)),
                MultiAgentState::new,
                assistantId);

        log.info("Checkpoint 已启用: RedisSaver {}:{} ttl={}h namespace={}",
                redisHost, redisPort, ttlHours, assistantId);
```

import 变化：

```java
// 删
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import java.util.concurrent.TimeUnit;

// 加
import com.best.ai_graph.checkpoint.CheckpointRedisOptions;
import com.best.ai_graph.checkpoint.GraphCheckpoints;
import java.time.Duration;
```

### 6.2 `AgentController` 的两段改成

```java
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String threadId = GraphThreadIds.of(req.sessionId());
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        Map<String, Object> input = new HashMap<>();
        input.put(MultiAgentState.MESSAGES, List.of(UserMessage.from(req.message())));
        input.put(MultiAgentState.USER_ID, userId == null ? "" : userId);
        input.put(MultiAgentState.PENDING_ACTION, "");
        input.put(MultiAgentState.APPROVED, false);

        // Map 重载已 @Deprecated(forRemoval)，必须包一层 GraphInput.args
        return run(() -> graph.invoke(GraphInput.args(input), config), threadId, config);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req) {
        String threadId = GraphThreadIds.of(req.sessionId());
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        log.info("收到审批: threadId={} approved={}", threadId, req.approved());

        return run(() -> graph.invoke(
                        GraphInput.resume(Map.of(MultiAgentState.APPROVED, req.approved())), config),
                threadId, config);
    }
```

`run()` 里的两处判断也可以收：

```java
    private Map<String, Object> run(GraphRunner runner, String threadId, RunnableConfig config) {
        try {
            Optional<MultiAgentState> result = runner.run();
            if (result.isEmpty()) {
                return Map.of("status", "error", "message", "图没有返回状态");
            }
            return respond(result.get(), threadId, config);
        } catch (Exception e) {
            if (GraphInterrupts.isInterrupt(e)) {          // ← 收口
                return waiting(threadId, config);
            }
            log.error("图执行失败 threadId={}", threadId, e);
            return Map.of("status", "error", "threadId", threadId, "message", e.getMessage());
        }
    }

    private Map<String, Object> respond(MultiAgentState state, String threadId, RunnableConfig config) {
        if (GraphInterrupts.waitingAt(graph, config, GATE)) {   // ← 收口（含 null 判定）
            return waiting(threadId, config);
        }
        return Map.of("status", "done", "threadId", threadId,
                "answer", lastAiText(state.messages()));
    }
```

`nextNodeOf(config)` 这个方法**可以删掉**（被 `GraphInterrupts.nextNodeId` 取代）✓

### 6.3 「接线」到底指什么

搬完模块**不等于生效**。现在有两个已知的"没通电"状态：

| 没接的线 | 症状 | 怎么接 |
|---|---|---|
| `keyNamingStrategy` 没配 | key 还是 `langgraph4j:thread:...`（无命名空间） | 步骤 6.1 的改造就是接线本身 |
| `GraphThreadIds` 没被调用 | sessionId 无校验、将来改格式要改多处 | 步骤 6.2 |
| `invoke(Map, config)` 已弃用 | 编译警告「已过时，且标记为待删除」 | 步骤 6.2 |
| **`approvalGate` 没 `addNode`** | **应用根本起不来** | 见交付单 25 第 2 节 |
| **`SupervisorNode` 转义写错** | 调度文本永远是字面量 | 见交付单 25 第 3 节 |
| **`/approve` 端点缺失** | 两段式跑不通 | 见交付单 25 第 4 节 |

**后面三条（25 号的 bug）优先级高于本模块搬家** —— 不修它们，服务起不来，搬家也无从验证。

---

## 7. 明确**不**进 `ai-graph` 的

| 不进 | 违反哪条判据 |
|---|---|
| 两段式 Controller 骨架 | **P1 + P2 都违**（读 `pendingAction` + 定义 `{status,...}` 响应格式） |
| 统一的 HTTP 响应体 DTO / 错误码 | P2（对外契约，该由业务或未来的 API 层定） |
| 记忆节点（`Recall`/`WriteMemoryNode`） | P1（依赖 `state.userId()` / `messages()`） |
| `SCHEMA` 定义模式 / state 基类 | P1（就是 state 本身） |
| `ChatMessageTexts` | P3 —— 它够格，但使用者横跨 ai-memory，共同上游是 **`ai-common`** |
| Spring 自动装配（`@AutoConfiguration`） | 不是判据问题，是**形态选择**：需要参数的东西不适合"引了就生效" |

---

## 8. 验证

```bash
# ① 编译（含弃用警告扫描）
mvn -o -DskipTests clean compile -Dmaven.compiler.showDeprecation=true

# ② 判据 P1 的物理验证 —— 必须是 0
grep -r "MultiAgentState" ai-graph/src/ | wc -l

# ③ 起服务，跑一次完整两段式
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"请删除上个月测试数据","sessionId":"s1"}'
#   期待 waiting_approval

curl -s -X POST http://localhost:8081/agent/approve \
  -H 'Content-Type: application/json' -d '{"sessionId":"s1","approved":true}'
#   期待 done

# ④ key 带上了命名空间
redis-cli --scan --pattern 'langgraph4j:*'
#   期待 langgraph4j:ai-multiagent:thread:name:s1:active 这种形状
```

**② 那一条是这次定案的核心** —— 它是 P1 判据的可执行形式。
如果哪天有人往 `ai-graph` 里塞了一个需要 `MultiAgentState` 的类，这条命令会立刻变成非 0。

---

## 一句话总结

> **`ai-graph` = 5 个类、零 `com.best` 依赖、零具体 state 认知。**
>
> 准入靠两条判据（**不认识具体 state** + **不定义对外契约**），
> 放哪个公共件靠第三条（**最靠近使用者的共同上游**）。
>
> 它**不是"图应用框架"**，是"langgraph4j 在这个平台里的适配薄壳" ——
> 这个定位一旦模糊，它就会开始长成第二个 `AiServices.Builder`。
