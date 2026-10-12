# 交付单 08 · AI-Common 观测性与 Token 统计

> **这一篇只动 `AI-Common`，和 01–07（AI-Memory）互不依赖。**
> 但 AI-Memory 依赖 AI-Common，所以**建议先做这一篇**，再回头做 01–07。
>
> **本篇目标**：每次模型调用都留下一条记录 —— 哪个助手、哪个用户、哪个会话、用了多少 token、花了多久、成没成。

---

## 你会得到什么

- 每次调用的 token 用量 + 耗时 + 成败，**流式也覆盖**
- usage 拿不到时**估算兜底**，并且用 `estimated` 字段明确标出来
- 两个记账出口：打日志（默认）/ 异步批量落库
- 每次调用的业务维度：`assistantId` / `tenantId` / `traceId`
- TTFT（首字延迟）采集 —— 流式体验最该看的指标

---

## 你会敲出什么

| # | 文件 | 动作 |
|---|---|---|
| 1 | `ai-common/pom.xml` | 改（加 3 个依赖） |
| 2 | `properties/AiObservabilityProperties.java` | 新建 |
| 3 | `observability/ModelUsage.java` | 新建 |
| 4 | `observability/AiCallContext.java` | 新建 |
| 5 | `observability/ModelUsageRecorder.java` | 新建 |
| 6 | `observability/ChatTokenUsageListener.java` | 新建 |
| 7 | `observability/LoggingModelUsageRecorder.java` | 新建 |
| 8 | `observability/AsyncModelUsageRecorder.java` | 新建 |
| 9 | `observability/MicrometerModelUsageRecorder.java` | 新建（可选） |
| 10 | `observability/web/AiCallContextFilter.java` | 新建 |
| 11 | `config/AiObservabilityWebAutoConfiguration.java` | 新建 |
| 12 | `register/ChatModelRegister.java` | 改（挂 listener） |
| 13 | `register/StreamingChatModelRegister.java` | 改（挂 listener） |
| 14 | `config/AiCommonAutoConfiguration.java` | 改（加 3 个 Bean） |
| 15 | `META-INF/spring/...AutoConfiguration.imports` | 改（加一行） |
| 16 | `assistant-demo` 的 `ChatStreamController` | 改（采集 TTFT） |
| 17 | `assistant-demo` 的 `application.yml` | 改（加一段） |

路径前缀统一 `ai-common/src/main/java/com/best/ai_common/`。

---

# 第零节 · 先花 10 分钟测一件事

**流式响应的 `usage` 拿不拿得到，决定后面走哪条路。** 不要跳这一步。

写个临时代码，三个 case 各试一次：

```java
var model = streamingRegister.getDefault();
model.chat("说三个字", new StreamingChatResponseHandler() {
    @Override public void onPartialResponse(String t) { }
    @Override public void onCompleteResponse(ChatResponse r) {
        System.out.println("tokenUsage = " + r.metadata().tokenUsage());
    }
    @Override public void onError(Throwable t) { t.printStackTrace(); }
});
```

| case | 怎么试 | 结果 |
|---|---|---|
| 直连厂商 | `base-url` 改厂商官方地址 | ？ |
| 经你的网关 | `base-url` 是 `ai-gateway` | ？ |
| 带参数 | 想办法塞 `stream_options.include_usage` | ？ |

**三种结果的处置**：

- **直连有、经网关没有** → 网关把 usage 吞了。**去找网关加一行透传,比任何估算都准。** 值得先问
- **带参数才有** → LangChain4j 没自动带。继续本篇,走估算兜底,同时并行去推网关
- **全都没有** → 走估算兜底（本篇第五节就是干这个的）

估算是兜底，不是首选 —— 它**永远是近似值**，不能拿去对账。

---

# 第一节 · 依赖与配置属性

## 1.1 改 `ai-common/pom.xml`

在 `<dependencies>` 里加：

```xml
        <!--
          Micrometer 的度量接口。optional：只有想接 Prometheus 才需要。
          注意它同时是 spring-boot-actuator 的依赖，通常你那里已经有了。
        -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
            <optional>true</optional>
        </dependency>

        <!--
          为了让 AiCallContextFilter 能编译（OncePerRequestFilter / jakarta.servlet）。
          optional：不是每个引 AI-Common 的模块都是 web 应用。
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <optional>true</optional>
        </dependency>

        <!--
          Micrometer 的绑定器（Gauge / FunctionCounter 的构建）。
          和 micrometer-core 一起 optional。
        -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-commons</artifactId>
            <optional>true</optional>
        </dependency>
```

> `OpenAiTokenCountEstimator` 用的 **jtokkit** 已经被 `langchain4j-open-ai` 传递进来了，不用单独引。

## 1.2 `properties/AiObservabilityProperties.java`

```java
package com.best.ai_common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 观测性配置。
 *
 * <p>全部字段都有默认值 —— 这个模块的设计目标是"引了就能用，
 * 不写任何配置也能跑"。
 */
@ConfigurationProperties("ai.observability")
public record AiObservabilityProperties(

        /** 取租户用哪个请求头 */
        @DefaultValue("X-Tenant-Id") String tenantHeader,

        /** 取链路 id 用哪个请求头（MDC 里有就优先用 MDC） */
        @DefaultValue("X-Trace-Id") String traceHeader,

        /**
         * provider 没返回 usage 时，要不要在本地估算。
         *
         * <p>⚠️ 设成 false 的话，拿不到 usage 就记 null —— 宁可留空，
         * 也不要用一个可能偏 20% 的数字去污染报表。
         * 这个开关本身就是个决策：你更怕"数据缺失"还是更怕"数据不准"。
         */
        @DefaultValue("true") Boolean estimateTokens,

        /**
         * 估算时借哪套 BPE 词表。
         *
         * <p>只是选词表用的。你的网关后面挂的是自研模型，没有对应词表，
         * 所以这里是借 OpenAI 的来估 —— 英文偏差小，中文通常偏大。
         */
        @DefaultValue("gpt-4o") String estimatorModel
) {}
```

---

# 第二节 · 三个基础类型

## 2.1 `observability/ModelUsage.java`

```java
package com.best.ai_common.observability;

import lombok.Builder;

/**
 * 一次模型调用的记账条目。
 *
 * <p>这份数据同时支撑三件事：用量计费、性能排障、按助手/租户的成本分摊。
 * 所以每个字段都要能回答一个具体问题 —— 不要往里塞"以后可能有用"的东西。
 */
@Builder
public record ModelUsage(

        /** 哪个助手。来自 spring.application.name */
        String assistantId,

        /** 哪个租户。来自请求头 */
        String tenantId,

        /** 链路 id。用来和业务日志串起来 */
        String traceId,

        /** 逻辑档位，如 free / fast。来自 listener 自带的 profile，不是从请求推的 */
        String profile,

        /** 真实模型名，取自 response metadata */
        String physicalModel,

        Integer inputTokens,

        Integer outputTokens,

        /**
         * ⚠️ 这两个 token 数是不是本地估算的。
         *
         * <p>为什么必须是独立字段而不是"null 就代表估算"：
         * null 有别的含义 —— "既没拿到 usage 也没估算"（比如估算被关掉了）。
         * 用 null 兼职表达两件事，报表就没法区分"缺失"和"不准"。
         *
         * <p>更要命的是：这个字段能救你一次。
         * 如果哪天网关开始返回 usage，你的数字会突然跳一档 ——
         * 有它你立刻知道原因，没它你会怀疑是自己代码改坏了。
         */
        boolean estimated,

        /** 总耗时。-1 表示没测到 */
        long latencyMs,

        boolean success,

        String errorMsg
) {}
```

## 2.2 `observability/AiCallContext.java`

```java
package com.best.ai_common.observability;

/**
 * 一次 AI 调用的业务上下文。
 *
 * <p>由 AiCallContextFilter 在请求入口写入，由 listener 在 onRequest 里快照。
 *
 * <p>⚠️ 它只保证在【调用线程】上正确。listener 的回调可能跑到模型的 I/O 线程上
 * （流式和异步路径尤其如此），那时 ThreadLocal 已经空了 —— 或者更糟，
 * 读到了另一个请求的值。所以它必须在 onRequest 里被快照进 attributes。
 */
public final class AiCallContext {

    /** 不可变快照 —— 保证跨线程传递时不会被改 */
    public record Snapshot(String assistantId, String tenantId, String traceId) {
        public static final Snapshot EMPTY = new Snapshot(null, null, null);
    }

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private AiCallContext() {}

    public static void set(Snapshot snapshot) {
        HOLDER.set(snapshot);
    }

    public static Snapshot current() {
        Snapshot s = HOLDER.get();
        return s == null ? Snapshot.EMPTY : s;
    }

    /**
     * 一定要在 finally 里调。
     * Tomcat 的线程是复用的，不清会串到下一个请求上 ——
     * 那个请求的用量就会记到上一个用户头上。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
```

## 2.3 `observability/ModelUsageRecorder.java`

```java
package com.best.ai_common.observability;

/**
 * 记账出口。
 *
 * <p>契约：实现必须是快的、非阻塞的。
 * 它会被 LangChain4j 在自己的回调线程上调用 —— 那个线程同时也是
 * 模型响应的处理线程，同步写库会直接拖垮吞吐。
 *
 * <p>要落库请用 {@link AsyncModelUsageRecorder}，不要在这里直接 JDBC。
 */
public interface ModelUsageRecorder {

    void record(ModelUsage usage);
}
```

## ✅ 检查点 8.1

```bash
mvn -q -pl AI-Common -am clean compile
```

---

# 第三节 · 监听器（含估算兜底）

## `observability/ChatTokenUsageListener.java`

```java
package com.best.ai_common.observability;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Map;

/**
 * 对话模型用量统计。
 *
 * <p>每个 profile 一个实例 —— 在 ChatModelRegister.create() 里 new 出来。
 * 这是刻意的：listener 自己带着 profile，不用从 request 里反推。
 * 如果做成全局单例，onResponse 里只能拿到物理模型名（best-1-nothinking），
 * 统计出来就是"按模型名分组"而不是"按你要看的逻辑档位分组"。
 *
 * <p>⚠️ 它同时被挂到 ChatModel 和 StreamingChatModel 上 —— 两者用的是
 * 同一个 ChatModelListener 接口，没有流式专用版本。
 */
public class ChatTokenUsageListener implements ChatModelListener {

    /** attributes 是 request 与 response 之间唯一的桥 */
    private static final String ATTR_START = "ai.start.nanos";
    private static final String ATTR_CTX   = "ai.call.context";

    private final String profile;
    private final ModelUsageRecorder recorder;

    /** 可为 null —— 为 null 时不做估算兜底，拿不到 usage 就记 null */
    private final TokenCountEstimator estimator;

    public ChatTokenUsageListener(String profile,
                                  ModelUsageRecorder recorder,
                                  TokenCountEstimator estimator) {
        this.profile = profile;
        this.recorder = recorder;
        this.estimator = estimator;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 此刻还在调用线程 —— 把 ThreadLocal 快照进 attributes。
        // 后面就算回调换了线程，业务上下文也丢不了。
        ctx.attributes().put(ATTR_CTX, AiCallContext.current());
        ctx.attributes().put(ATTR_START, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        AiCallContext.Snapshot snap = snapshotOf(ctx.attributes());
        ChatResponse resp = ctx.chatResponse();
        TokenUsage usage = resp.metadata().tokenUsage();

        Integer input;
        Integer output;
        boolean estimated;

        if (usage != null) {
            // 正常路径：provider 给了准确值
            input = usage.inputTokenCount();
            output = usage.outputTokenCount();
            estimated = false;
        } else if (estimator != null) {
            // 兜底路径：本地估算。
            // 触发条件通常是流式响应 —— OpenAI 协议流式默认不返回 usage。
            input = estimator.estimateTokenCountInMessages(ctx.chatRequest().messages());
            String text = resp.aiMessage().text();
            // 模型返回工具调用而不是文本时 text() 是 null，估算会 NPE
            output = estimator.estimateTokenCountInText(text == null ? "" : text);
            estimated = true;
        } else {
            // 没拿到 usage，也不估算 —— 如实记 null，不编数
            input = null;
            output = null;
            estimated = false;
        }

        recorder.record(ModelUsage.builder()
                .assistantId(snap.assistantId())
                .tenantId(snap.tenantId())
                .traceId(snap.traceId())
                .profile(profile)
                .physicalModel(resp.metadata().modelName())
                .inputTokens(input)
                .outputTokens(output)
                .estimated(estimated)
                .latencyMs(elapsedMs(ctx.attributes()))
                .success(true)
                .build());
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        AiCallContext.Snapshot snap = snapshotOf(ctx.attributes());

        recorder.record(ModelUsage.builder()
                .assistantId(snap.assistantId())
                .tenantId(snap.tenantId())
                .traceId(snap.traceId())
                .profile(profile)
                .latencyMs(elapsedMs(ctx.attributes()))
                .success(false)
                .errorMsg(ctx.error().getMessage())
                .build());
    }

    private static AiCallContext.Snapshot snapshotOf(Map<Object, Object> attrs) {
        Object v = attrs.get(ATTR_CTX);
        return v instanceof AiCallContext.Snapshot s ? s : AiCallContext.Snapshot.EMPTY;
    }

    private static long elapsedMs(Map<Object, Object> attrs) {
        Object start = attrs.get(ATTR_START);
        return start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : -1L;
    }
}
```

> **将来加 Embedding 的统计时**：接口不同（是 `EmbeddingModelListener`），
> 但 `snapshotOf` / `elapsedMs` 这两个私有方法会重复。到时候把它们抽成一个包内可见的
> `ListenerSupport` 工具类。现在只有一个 listener，不抽。

## ✅ 检查点 8.2

```bash
mvn -q -pl AI-Common -am clean compile
```

---

# 第四节 · 三个记账出口（选一个）

## 4.1 `observability/LoggingModelUsageRecorder.java`（默认）

```java
package com.best.ai_common.observability;

import lombok.extern.slf4j.Slf4j;

/**
 * 默认出口：打日志。
 * 保证开箱能跑，且因为不落库所以绝不阻塞回调线程。
 */
@Slf4j
public class LoggingModelUsageRecorder implements ModelUsageRecorder {

    @Override
    public void record(ModelUsage u) {
        if (u.success()) {
            // estimated 直接打在日志里 —— 看日志时一眼能分辨这个数准不准
            log.info("AI 用量 | 助手={} 租户={} 档位={} 模型={} 入={} 出={} 估算={} 耗时={}ms 链路={}",
                    u.assistantId(), u.tenantId(), u.profile(), u.physicalModel(),
                    u.inputTokens(), u.outputTokens(), u.estimated(),
                    u.latencyMs(), u.traceId());
        } else {
            log.warn("AI 失败 | 助手={} 档位={} 耗时={}ms 错误={}",
                    u.assistantId(), u.profile(), u.latencyMs(), u.errorMsg());
        }
    }
}
```

## 4.2 `observability/AsyncModelUsageRecorder.java`（要落库时用）

```java
package com.best.ai_common.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 异步批量记账。把「回调线程」和「慢的落库」解耦。
 *
 * <p>整条路径上没有一次阻塞调用 —— 这是它唯一的设计目标。
 * 代价是队列满时会丢数据，所以丢弃量必须挂到监控上。
 */
@Slf4j
public class AsyncModelUsageRecorder implements ModelUsageRecorder, SmartLifecycle {

    /** 按「你能接受丢失的时间窗口」倒推：8192 ≈ 100 次/秒 × 82 秒 */
    public static final int DEFAULT_CAPACITY = 8192;

    /** 批量落地的出口。收到的一定是非空 List。 */
    private final Consumer<List<ModelUsage>> sink;

    private final int  capacity;
    private final int  batchSize;
    private final long flushIntervalMs;
    private final long shutdownDrainMs;

    /**
     * ArrayBlockingQueue 而非 LinkedBlockingQueue：前者预分配数组、无节点对象、
     * 必然有界；后者的默认容量是 Integer.MAX_VALUE（无界），高吞吐下会吃光内存。
     */
    private final BlockingQueue<ModelUsage> queue;

    private final AtomicLong accepted      = new AtomicLong();
    private final AtomicLong dropped       = new AtomicLong();
    private final AtomicLong written       = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    private volatile Thread  worker;
    private volatile boolean running;

    public AsyncModelUsageRecorder(Consumer<List<ModelUsage>> sink) {
        this(sink, DEFAULT_CAPACITY, 500, 1000L, 3000L);
    }

    public AsyncModelUsageRecorder(Consumer<List<ModelUsage>> sink,
                                   int capacity,
                                   int batchSize,
                                   long flushIntervalMs,
                                   long shutdownDrainMs) {
        if (batchSize <= 0 || batchSize > capacity) {
            throw new IllegalArgumentException("batchSize 必须在 (0, capacity] 之间");
        }
        this.sink = sink;
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.shutdownDrainMs = shutdownDrainMs;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    // ======================================================== 写入口

    @Override
    public void record(ModelUsage usage) {
        if (usage == null) {
            return;
        }

        // offer 而非 put：这个方法跑在模型回调线程上，
        // 阻塞它等于让用户的对话卡在那里等一根日志落地。
        // 统计数据的价值远小于对话延迟 —— 宁可丢，不能等。
        if (queue.offer(usage)) {
            accepted.incrementAndGet();
            return;
        }

        long n = dropped.incrementAndGet();
        // 只在 1/2/4/8/16... 上打日志。队列满时丢弃是每秒上千次的，
        // 每次都打会冲爆日志、而且打日志本身变成新的瓶颈。
        if (Long.bitCount(n) == 1) {
            log.warn("AI 用量队列已满（容量 {}），累计丢弃 {} 条", capacity, n);
        }
    }

    // ======================================================== 消费

    private void consume() {
        while (running) {
            try {
                // 带超时而非 take()：take() 会永久阻塞，
                // 消费线程就再也看不到 running = false 了
                ModelUsage first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }

                List<ModelUsage> batch = new ArrayList<>(batchSize);
                batch.add(first);
                // drainTo 是非阻塞的：有多少捞多少，凑不满也不等
                queue.drainTo(batch, batchSize - 1);

                sink.accept(batch);
                written.addAndGet(batch.size());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;                                  // 停机信号，正常退出

            } catch (Throwable t) {
                // 必须吞掉一切。消费线程一旦死掉，用量会静默堆到队列满、
                // 然后全部丢弃，而且没有任何人知道 —— 这是最安静的故障。
                failedBatches.incrementAndGet();
                log.error("消费 AI 用量失败，本批已丢弃", t);
            }
        }
    }

    // ======================================================== 生命周期

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::consume, "ai-usage-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("AI 用量异步记账已启动：容量={} 批大小={} 刷新间隔={}ms",
                capacity, batchSize, flushIntervalMs);
    }

    /**
     * getPhase() 的语义是双向的，这点最容易漏：
     * <ul>
     *   <li>start 阶段：phase 小的先启动 → MAX_VALUE 最后启动，依赖都已就绪</li>
     *   <li>stop 阶段：phase 大的先停止 → MAX_VALUE 最先停止，
     *       赶在 DataSource 被关掉之前把数据刷出去</li>
     * </ul>
     * 一个 getPhase() 同时管住两个方向 —— 这就是 SmartLifecycle
     * 相对 @PreDestroy 的全部价值。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;

        Thread t = worker;
        if (t != null) {
            t.interrupt();                                   // 打断 poll 的阻塞
            try {
                t.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        drainRemaining();
        log.info("AI 用量异步记账已停止：{}", stats());
    }

    /** 停机时尽力排空，但设死上限 —— 别让停机变成卡住 */
    private void drainRemaining() {
        long deadline = System.currentTimeMillis() + shutdownDrainMs;
        List<ModelUsage> batch = new ArrayList<>(batchSize);

        while (System.currentTimeMillis() < deadline) {
            batch.clear();
            int n = queue.drainTo(batch, batchSize);
            if (n == 0) {
                break;
            }
            try {
                sink.accept(batch);
                written.addAndGet(n);
            } catch (Throwable e) {
                log.error("停机收尾落库失败，剩余 {} 条丢弃", queue.size(), e);
                break;                                       // 别再试，直接放弃
            }
        }

        int left = queue.size();
        if (left > 0) {
            // K8s 滚动更新时停机时间是硬约束，超了会被 SIGKILL。
            // 宁可丢统计，不能拖慢发布。
            dropped.addAndGet(left);
            log.warn("停机时仍有 {} 条用量未落库", left);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ======================================================== 观测

    public UsageStats stats() {
        return new UsageStats(accepted.get(), dropped.get(), written.get(),
                failedBatches.get(), queue.size());
    }

    public record UsageStats(long accepted, long dropped, long written,
                             long failedBatches, int queueSize) {}

    @Override
    public String toString() {
        return "AsyncModelUsageRecorder" + stats();
    }
}
```

## 4.3 `observability/MicrometerModelUsageRecorder.java`（可选，接 Prometheus）

```java
package com.best.ai_common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接 Prometheus / Micrometer 的出口。
 * 纯内存自增，不阻塞回调线程。
 *
 * <p>common 不自动注册它 —— 想用就自己声明一个 ModelUsageRecorder Bean，
 * 默认的 LoggingModelUsageRecorder 会自动让位。
 */
public class MicrometerModelUsageRecorder implements ModelUsageRecorder {

    private final MeterRegistry registry;

    /** Counter 的构造不便宜，按标签组合缓存 */
    private final Map<String, Counter> cache = new ConcurrentHashMap<>();

    public MicrometerModelUsageRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(ModelUsage u) {
        if (!u.success()) {
            counterOf("ai.calls.errors", u).increment();
            return;
        }
        counterOf("ai.calls", u).increment();
        if (u.inputTokens() != null) {
            counterOf("ai.tokens.input", u).increment(u.inputTokens());
        }
        if (u.outputTokens() != null) {
            counterOf("ai.tokens.output", u).increment(u.outputTokens());
        }
    }

    private Counter counterOf(String name, ModelUsage u) {
        String key = name + '|' + nvl(u.assistantId()) + '|' + nvl(u.profile())
                + '|' + nvl(u.physicalModel());
        return cache.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("assistant", nvl(u.assistantId()))
                .tag("profile", nvl(u.profile()))
                .tag("model", nvl(u.physicalModel()))
                // 估算值单独打一个标签：看板上一眼能分辨准不准
                .tag("estimated", String.valueOf(u.estimated()))
                .register(registry));
    }

    private static String nvl(String s) {
        return s == null ? "unknown" : s;
    }
}
```

---

# 第五节 · 上下文过滤器

## 5.1 `observability/web/AiCallContextFilter.java`

```java
package com.best.ai_common.observability.web;

import com.best.ai_common.observability.AiCallContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把「谁在问」写进 AiCallContext，供 listener 快照使用。
 * 由 AiObservabilityWebAutoConfiguration 自动注册。
 */
public class AiCallContextFilter extends OncePerRequestFilter {

    private final String assistantId;
    private final String tenantHeader;
    private final String traceHeader;

    public AiCallContextFilter(String assistantId, String tenantHeader, String traceHeader) {
        this.assistantId = assistantId;
        this.tenantHeader = tenantHeader;
        this.traceHeader = traceHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        AiCallContext.set(new AiCallContext.Snapshot(
                assistantId,
                req.getHeader(tenantHeader),
                resolveTraceId(req)));
        try {
            chain.doFilter(req, resp);
        } finally {
            // 必须清 —— Tomcat 线程是复用的，不清会串到下一个请求上
            AiCallContext.clear();
        }
    }

    /**
     * 优先取链路追踪组件写进 MDC 的 traceId（SkyWalking / Micrometer Tracing），
     * 没有再回落到请求头。这样即使上游没传头，也能和链路追踪对上。
     */
    private String resolveTraceId(HttpServletRequest req) {
        String fromMdc = MDC.get("traceId");
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return req.getHeader(traceHeader);
    }
}
```

## 5.2 `config/AiObservabilityWebAutoConfiguration.java`

```java
package com.best.ai_common.config;

import com.best.ai_common.observability.web.AiCallContextFilter;
import com.best.ai_common.properties.AiObservabilityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 注册业务上下文过滤器。
 *
 * <p>单独一个 auto-config 类，是因为它有额外的类路径前提（servlet + spring-web）。
 * 非 web 的助手引了这个 jar 也不会炸 —— 这正是 @ComponentScan 做不到的事。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "jakarta.servlet.Filter",
        "org.springframework.boot.web.servlet.FilterRegistrationBean"
})
public class AiObservabilityWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiCallContextFilter aiCallContextFilter(
            AiObservabilityProperties props,
            @Value("${spring.application.name:unknown}") String assistantId) {
        return new AiCallContextFilter(
                assistantId, props.tenantHeader(), props.traceHeader());
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<AiCallContextFilter> aiCallContextFilterRegistration(
            AiCallContextFilter filter) {
        FilterRegistrationBean<AiCallContextFilter> reg = new FilterRegistrationBean<>(filter);
        // 尽量早：要在所有业务 Filter 之前把上下文准备好
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
```

---

# 第六节 · 给两个 register 挂上 listener

## 6.1 改 `register/ChatModelRegister.java`（整个文件）

```java
package com.best.ai_common.register;

import com.best.ai_common.observability.ChatTokenUsageListener;
import com.best.ai_common.observability.ModelUsageRecorder;
import com.best.ai_common.properties.ChatModelProperties;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;

/**
 * 对话模型注册器。
 *
 * <p>用 OpenAiChatModel 而不是各厂商专用类，是因为上游是 ai-gateway，
 * 它统一暴露 OpenAI 兼容协议。
 *
 * <p>构造器有两个新增参数：recorder（记账出口）和 estimator（估算器，可为 null）。
 */
public class ChatModelRegister extends ModelRegister<ChatModel> {

    private final ChatModelProperties properties;
    private final ModelUsageRecorder recorder;
    /** 可为 null：表示不做本地估算 */
    private final TokenCountEstimator estimator;

    /**
     * 兼容旧签名的构造器 —— 不动观测性时可以用这个。
     * 新代码请用下面那个三参数的。
     */
    public ChatModelRegister(ChatModelProperties properties, ModelUsageRecorder recorder) {
        this(properties, recorder, null);
    }

    public ChatModelRegister(ChatModelProperties properties,
                             ModelUsageRecorder recorder,
                             TokenCountEstimator estimator) {
        this.properties = properties;
        this.recorder = recorder;
        this.estimator = estimator;
    }

    @Override
    public String DefaultProfile() {
        return properties.profile();
    }

    @Override
    protected ChatModel create(String profile) {
        ChatModelProperties.Endpoint endpoint = properties.getEndpoint(profile);

        return OpenAiChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.modelName())
                .temperature(endpoint.temperature() != null
                        ? endpoint.temperature() : properties.temperature())
                .maxTokens(endpoint.maxTokens() != null
                        ? endpoint.maxTokens() : properties.maxTokens())
                .timeout(endpoint.timeout() != null
                        ? endpoint.timeout() : properties.timeout())
                // 关键：listener 在这里 new，所以它知道自己属于哪一档。
                // 做成全局单例挂到所有模型上的话，统计里就只剩物理模型名了。
                .listeners(List.of(new ChatTokenUsageListener(profile, recorder, estimator)))
                .build();
    }
}
```

## 6.2 改 `register/StreamingChatModelRegister.java`（整个文件）

```java
package com.best.ai_common.register;

import com.best.ai_common.observability.ChatTokenUsageListener;
import com.best.ai_common.observability.ModelUsageRecorder;
import com.best.ai_common.properties.ChatModelProperties;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.List;

/**
 * 流式对话模型注册器。
 *
 * <p>和 ChatModelRegister 共用同一份 ChatModelProperties —— 这是刻意的。
 * profile 描述的是「用哪一档模型」，不是「用哪种调用方式」，
 * 所以同一个 profile 既能造阻塞版也能造流式版，配置只写一份。
 *
 * <p>listener 用的还是 ChatModelListener —— 流式没有专用版本。
 * 流式下 onResponse 会在流结束时触发一次。
 */
public class StreamingChatModelRegister extends ModelRegister<StreamingChatModel> {

    private final ChatModelProperties properties;
    private final ModelUsageRecorder recorder;
    private final TokenCountEstimator estimator;

    public StreamingChatModelRegister(ChatModelProperties properties,
                                      ModelUsageRecorder recorder) {
        this(properties, recorder, null);
    }

    public StreamingChatModelRegister(ChatModelProperties properties,
                                      ModelUsageRecorder recorder,
                                      TokenCountEstimator estimator) {
        this.properties = properties;
        this.recorder = recorder;
        this.estimator = estimator;
    }

    @Override
    public String DefaultProfile() {
        return properties.profile();
    }

    @Override
    protected StreamingChatModel create(String profile) {
        ChatModelProperties.Endpoint endpoint = properties.getEndpoint(profile);

        return OpenAiStreamingChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.modelName())
                .temperature(endpoint.temperature() != null
                        ? endpoint.temperature() : properties.temperature())
                // 注意这里的方法名是 maxTokens，和 OpenAiChatModel 的 builder 一致，
                // 别写成 maxOutputTokens
                .maxTokens(endpoint.maxTokens() != null
                        ? endpoint.maxTokens() : properties.maxTokens())
                .timeout(endpoint.timeout() != null
                        ? endpoint.timeout() : properties.timeout())
                // ⚠️ 流式的 token 统计是最容易出问题的：usage 可能压根拿不到，
                // 那时 listener 会走估算兜底并把 estimated 标成 true。
                // 实测方法见本篇第零节。
                .listeners(List.of(new ChatTokenUsageListener(profile, recorder, estimator)))
                .build();
    }
}
```

---

# 第七节 · 自动装配

## 7.1 改 `config/AiCommonAutoConfiguration.java`（整个文件）

```java
package com.best.ai_common.config;

import com.best.ai_common.observability.LoggingModelUsageRecorder;
import com.best.ai_common.observability.ModelUsageRecorder;
import com.best.ai_common.properties.AiObservabilityProperties;
import com.best.ai_common.properties.ChatModelProperties;
import com.best.ai_common.properties.EmbeddingModelProperties;   // 若已做交付单 05
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_common.register.EmbeddingModelRegister;        // 若已做交付单 05
import com.best.ai_common.register.StreamingChatModelRegister;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({
        ChatModelProperties.class,
        AiObservabilityProperties.class,
        EmbeddingModelProperties.class       // 若还没做交付单 05，先删掉这一行
})
public class AiCommonAutoConfiguration {

    // ============================================ 观测性

    /**
     * 记账出口的默认实现：打日志。
     * 使用方自己声明一个 ModelUsageRecorder Bean（AsyncModelUsageRecorder /
     * MicrometerModelUsageRecorder）就会自动覆盖它。
     */
    @Bean
    @ConditionalOnMissingBean(ModelUsageRecorder.class)
    public ModelUsageRecorder modelUsageRecorder() {
        return new LoggingModelUsageRecorder();
    }

    /**
     * token 估算器。
     *
     * <p>注意它借的是 OpenAI 的词表（jtokkit），因为网关后面是自研模型、
     * 没有对应词表。英文偏差小，中文通常偏大 —— 只用于相对比较和告警。
     *
     * <p>关掉它（ai.observability.estimate-tokens=false）的效果是：
     * 拿不到 usage 时记 null，而不是记一个可能偏 20% 的数。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai.observability", name = "estimate-tokens",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public TokenCountEstimator tokenCountEstimator(AiObservabilityProperties props) {
        return new OpenAiTokenCountEstimator(props.estimatorModel());
    }

    // ============================================ 模型

    @Bean
    @ConditionalOnProperty(prefix = "ai.chat", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public ChatModelRegister chatModelRegister(ChatModelProperties properties,
                                               ModelUsageRecorder recorder,
                                               ObjectProvider<TokenCountEstimator> estimator) {
        // getIfAvailable()：估算器被 @ConditionalOnProperty 关掉时返回 null，
        // listener 内部会处理 null（不估算，如实记 null）
        return new ChatModelRegister(properties, recorder, estimator.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.chat", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public StreamingChatModelRegister streamingChatModelRegister(
            ChatModelProperties properties,
            ModelUsageRecorder recorder,
            ObjectProvider<TokenCountEstimator> estimator) {
        return new StreamingChatModelRegister(properties, recorder, estimator.getIfAvailable());
    }

    // ============================================ 向量（交付单 05 加的，还没有就删掉）

    @Bean
    @ConditionalOnProperty(prefix = "ai.embed", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public EmbeddingModelRegister embeddingModelRegister(EmbeddingModelProperties properties) {
        return new EmbeddingModelRegister(properties);
    }
}
```

## 7.2 改 `META-INF/spring/...AutoConfiguration.imports`

```
com.best.ai_common.config.AiCommonAutoConfiguration
com.best.ai_common.config.AiObservabilityWebAutoConfiguration
```

## ✅ 检查点 8.3

```bash
mvn -q clean compile
```

---

# 第八节 · TTFT 采集（改 assistant-demo）

## 8.1 为什么必须在这层做

`ChatModelListener` 只有 `onRequest` / `onResponse` / `onError`,**没有 token 级回调**。流式下 `onResponse` 只在流结束时触发一次。

所以「用户多久看到第一个字」这个指标，**观测层拿不到**，只能在业务侧记。

**为什么它比总耗时重要**:你会盯着"总耗时 3.2 秒"发愁,但用户可能 0.4 秒就看到第一个字了,体感完全没问题。

## 8.2 改 `assistant-demo/.../controller/ChatStreamController.java`

在 `stream()` 方法里改三处：

```java
        // ① 记开始时刻，和 SseEmitter 的创建放在一起
        long startedAt = System.currentTimeMillis();
        // AtomicLong 而不是 long：回调在别的线程上，普通 long 的可见性没保证
        AtomicLong firstTokenAt = new AtomicLong(0);

        String memoryId = SessionKey.of(userId, sessionId).toMemoryId();

        assistant.chat(memoryId, message)
                .onPartialResponse(token -> {
                    if (finished.get()) {
                        return;
                    }
                    // ② TTFT：compareAndSet 保证只记第一次
                    if (firstTokenAt.compareAndSet(0, System.currentTimeMillis())) {
                        log.info("TTFT={}ms memoryId={}", firstTokenAt.get() - startedAt, memoryId);
                    }
                    try {
                        emitter.send(token);
                    } catch (IOException | IllegalStateException e) {
                        log.warn("推送 token 失败，客户端可能已断开: {}", e.toString());
                        finished.set(true);
                    }
                })
                .onCompleteResponse(response -> {
                    // ③ 顺手记一下总耗时和首字延迟的差值 —— 这个差值就是"生成阶段"的耗时
                    long total = System.currentTimeMillis() - startedAt;
                    log.info("流式完成 | 总耗时={}ms TTFT={}ms 生成阶段={}ms",
                            total, firstTokenAt.get() == 0 ? -1 : firstTokenAt.get() - startedAt,
                            firstTokenAt.get() == 0 ? -1 : total - (firstTokenAt.get() - startedAt));
                    if (finished.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                })
                .onError(error -> { /* 原样不动 */ })
                .start();
```

需要的 import：

```java
import java.util.concurrent.atomic.AtomicLong;
```

---

# 第九节 · 配置

## 改 `assistant-demo/src/main/resources/application.yml`

```yaml
ai:
  observability:
    tenant-header: X-Tenant-Id
    trace-header: X-Trace-Id
    # provider 没返回 usage 时要不要本地估算。
    # 更怕"数据缺失"→ 留 true；更怕"数据不准"→ 改成 false
    estimate-tokens: true
    # 借哪套 BPE 词表估算。中文偏差偏大，只做相对比较
    estimator-model: gpt-4o
```

## 落库的话再加这段（自定义 `ModelUsageRecorder` Bean）

```java
@Configuration
public class UsageRecorderConfig {

    private static final String SQL = """
            INSERT INTO ai_model_usage
                (assistant_id, tenant_id, trace_id, profile, physical_model,
                 input_tokens, output_tokens, estimated, latency_ms, success,
                 error_msg, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?, NOW())
            """;

    @Bean
    public ModelUsageRecorder modelUsageRecorder(JdbcTemplate jdbc) {
        return new AsyncModelUsageRecorder(
                batch -> jdbc.batchUpdate(SQL, batch, batch.size(), (ps, u) -> {
                    ps.setString(1, u.assistantId());
                    ps.setString(2, u.tenantId());
                    ps.setString(3, u.traceId());
                    ps.setString(4, u.profile());
                    ps.setString(5, u.physicalModel());
                    ps.setObject(6, u.inputTokens());
                    ps.setObject(7, u.outputTokens());
                    ps.setBoolean(8, u.estimated());
                    ps.setLong(9, u.latencyMs());
                    ps.setBoolean(10, u.success());
                    ps.setString(11, u.errorMsg());
                }),
                8192,    // 队列容量
                500,     // 每批最多 500 条
                1000L,   // 最长 1 秒刷一次
                3000L);  // 停机最多花 3 秒排空
    }
}
```

```sql
CREATE TABLE ai_model_usage (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    assistant_id   VARCHAR(64),
    tenant_id      VARCHAR(64),
    trace_id       VARCHAR(64),
    profile        VARCHAR(64)   COMMENT '逻辑档位',
    physical_model VARCHAR(128)  COMMENT '真实模型名',
    input_tokens   INT,
    output_tokens  INT,
    estimated      TINYINT(1)    COMMENT '1=这两个数是本地估算的',
    latency_ms     BIGINT,
    success        TINYINT(1),
    error_msg      VARCHAR(512),
    created_at     DATETIME(3),
    INDEX idx_assistant_time (assistant_id, created_at),
    INDEX idx_tenant_time    (tenant_id, created_at)
) ENGINE = InnoDB COMMENT 'AI 调用用量台账';
```

## ✅ 检查点 8（总）

先把 fallback 走通（不落库）：保持默认的 `LoggingModelUsageRecorder`，启动后调一次：

```bash
curl -N "http://localhost:8080/chat/stream?userId=u1&sessionId=s1&message=你好"
```

日志里应该出现：

```
AI 用量 | 助手=assistant-demo 租户=null 档位=free 模型=best-1-nothinking 入=37 出=12 估算=true 耗时=812ms 链路=null
TTFT=412ms memoryId=u1::s1
流式完成 | 总耗时=812ms TTFT=412ms 生成阶段=400ms
```

三个字段重点看：

| 字段 | 期望 | 不对怎么办 |
|---|---|---|
| `估算=true` | 说明没拿到 provider 的 usage，走的是本地估算 | 想拿准确值 → 回第零节，去推网关透传 |
| `入/出` 是数字 | 估算生效 | 都是 null → `estimate-tokens` 被关了，或者 estimator Bean 没装上 |
| `TTFT` 有值且远小于总耗时 | 正常 | 两个数一样大 → 说明 `compareAndSet` 写错了，记成了最后一次 |

---

# 自查清单

- [ ] `mvn -q clean compile` 通过
- [ ] `curl -N` 之后，日志里能看到 `AI 用量` 那一行
- [ ] 那一行里的 `档位` 是 `free`（逻辑档位），不是 `best-1-nothinking`（物理模型名）
- [ ] `估算` 字段有值，且你**知道它为什么是那个值**
- [ ] 日志里能看到 `TTFT`，且它明显小于总耗时
- [ ] 你能说出 `estimated` 这个字段不用 `null` 表达的**两个**理由
- [ ] 你能说出为什么 `TTFT` 必须在业务侧记，而不是在 listener 里

最后两条的答案：

- **`estimated` 不能用 null 兼职**：① null 已经有别的含义（"既没拿到 usage 也没估算"），一个字段兼职表达两个意思，报表就没法区分"缺失"和"不准"；② 如果哪天网关开始返回 usage，数字会突然跳一档，有这个字段你立刻知道原因
- **TTFT 只能在业务侧**：`ChatModelListener` 没有 token 级回调，流式下 `onResponse` 只在流结束时触发一次。这层只有"总耗时"

---

# 这一层的三个已知限制（做完要认）

1. **只覆盖 Java 侧调用方。** 脚本、别的语言写的服务调网关时不会经过这里
2. **值的准确性取决于网关行为。** 网关哪天开始返回 usage，你的统计会从"估算"变"准确"，数字跳一档
3. **拿不到 TTFT。** 这是接口层面的限制，不是实现问题。已经在第八节用业务侧埋点补上了

这三条不解决，只是要知道边界在哪。**别拿这里的数去对账计费** —— 对账要用网关的账单。
