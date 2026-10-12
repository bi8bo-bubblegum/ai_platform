# 交付单 · 第三批改动（含流式 token 统计的正面解法）

> 覆盖 `14-代码审核-第二轮.md` 和 `15-代码审核-第三轮.md` 里所有还没给过完整代码的改动。
> （13 号已给过 P0-1 / P0-2 / P1-1 / P1-2 的完整代码，本文不重复。）
>
> **⚠️ 本文第零节推翻了 15 号的 P0-5 结论。** 那条说"流式拿不到 userId，只能交给网关"——
> 实测下来**能拿到，而且不用网关**。下面给出证据和新代码。

---

# 第零节 · 流式输出如何统计用户 token（不考虑网关）

## 0.1 先给结论

**能做，改动量只有 15 行 —— 给 `TokenUsageListener` 补一个 `onRequest` 方法。**

不需要网关，不需要包装类，不需要改 `AiServices`。

## 0.2 三个实测证据

我反编译了本机 `langchain4j-core-1.20.0.jar` 和 `langchain4j-open-ai-1.20.0-sources.jar`，
三条事实如下（都能复现）：

### 证据一：流式响应**已经默认带 usage**

`OpenAiStreamingChatModel` 第 197 行：

```java
private ChatCompletionRequest createOpenAiRequest(ChatRequest request, ...) {
    return toOpenAiChatRequest(request, parameters, ...)
            .stream(true)
            .streamOptions(StreamOptions.builder().includeUsage(true).build())   // ★ 这里
            .build();
}
```

**LangChain4j 1.20 已经自动帮你带了 `stream_options: {include_usage: true}`。**

> 这修正了 04 号交付单里的一个说法。当时写的是"流式默认不返回 usage，需要手动带
> `stream_options`" —— 在**裸 HTTP** 层面是对的，但 LangChain4j 已经替我们做了。
> 所以只要服务端（厂商或网关）认这个参数并原样返回 usage，`onCompleteResponse` 里的
> `metadata().tokenUsage()` 就是有值的。

### 证据二：`onRequest` 在**调用线程**上执行

这是最关键的一条。`StreamingChatModel.chat(ChatRequest, handler)` 是**接口的 default 方法**，
字节码如下（`javap -c`）：

```
45: invokeinterface listeners()
53: new java/util/concurrent/ConcurrentHashMap          ← 创建 attributes
59: invokevirtual ChatRequestOptions.listenerAttributes()
67: new StreamingChatModel$1                             ← 包装 handler，把 attributes 传进去
...
96: invokestatic ChatModelListenerUtils.onRequest(ChatRequest, ModelProvider, Map, List)  ← ★ 触发 onRequest
104: invokeinterface doChat(ChatRequest, StreamingChatResponseHandler)                     ← 然后才发起异步调用
```

注意顺序：**先 `onRequest`，后 `doChat`。**

而 `doChat` 才是真正发起异步 HTTP 的地方（`OpenAiStreamingChatModel.doChat` 里
`client.chatCompletion(...).execute()`）。也就是说：

| 阶段 | 线程 | 说明 |
|---|---|---|
| `chat(...)` default 方法 → `onRequest` | **调用线程** | 业务代码调 `TokenStream.start()` 的那一行同步执行 |
| `doChat(...)` 之后的 HTTP 回调 → `onResponse` | **模型 I/O 线程** | OkHttp 的线程 |

**所以 `onRequest` 里读 `AiUserContext.get()` 是可以的**，而 `onResponse` 里不行 ——
这正是当前代码的问题所在。

> 顺带确认了 15 号那条观察的另一半：`OpenAiStreamingChatModel.doChat()` 里
> **完全没有触发 listener**（grep 不到 `onRequest`），listener 全部由接口的 default 方法触发。
> 所以这个"调用线程执行 onRequest"的行为是**框架保证**，不是某个 provider 的实现细节。

### 证据三：`attributes` 是 `ConcurrentHashMap`，跨线程安全

字节码 53-65 行：

```java
new ConcurrentHashMap<>(options.listenerAttributes())
```

而且**同一个 Map 实例**被传进 `StreamingChatModel$1`（那个包装 handler 的匿名内部类），
所以从 `onRequest` 写入、在 `onResponse` 读出，用的是同一个对象。

**`ConcurrentHashMap` 保证了"调用线程写、I/O 线程读"是安全的。**

## 0.3 所以方案就是：在 `onRequest` 里快照

```
请求线程                              模型 I/O 线程
---------                            -------------
preHandle: AiUserContext.set(u1)

onRequest（★ 调用线程）
  → attributes.put("ai.user.id", AiUserContext.get())     ← 在这里读，读得到
  → doChat() 发起异步 HTTP
                                     onResponse（I/O 线程）
                                       → attributes.get("ai.user.id")   ← 从 Map 取，拿得到
                                       → 记台账，user_id = u1 ✓
```

**这就是 08 号交付单里原本设计的方案**（"在 `onRequest` 里快照进 `attributes`"）——
只是后来实现 `TokenUsageListener` 时漏掉了 `onRequest`，直接在 `onResponse` 里读了 ThreadLocal。

## 0.4 为什么不用包装 `StreamingChatModel`

我在 15 号里说"装饰器那条路是死的"，那个结论对，但当时没想到 `onRequest` 这条路。
现在两条路都能走通，对比一下：

| | 补 `onRequest`（推荐） | 包装 `StreamingChatModel` |
|---|---|---|
| 改动量 | 一个方法，~10 行 | 新增一个类，要实现/转发 6-7 个接口方法 |
| 需要覆盖两个类吗 | 不用（listener 对 `ChatModel` / `StreamingChatModel` 通用） | **要**（`ChatModel` 和 `StreamingChatModel` 各一个包装） |
| 闭包捕获 userId | 不需要（走 attributes） | 需要（在 `chat()` 里捕获，再在匿名 handler 里用） |
| 与现有代码的关系 | 就在原来的类里加 | 要改 `ChatModelRegister` / `StreamingChatModelRegister` 的构建代码 |
| 风险 | 低 | 包装层要正确转发 `defaultRequestParameters()`，转发错了会影响参数合并 |

**包装类能工作**（原理是把 userId 闭包进匿名 handler，跨线程传递），但**多写两个类、多一层转发**，
换来的是同样的效果。所以选**补 `onRequest`**。

## 0.5 `TokenUsageListener` 完整代码（替换整个文件）

路径：`ai-common/src/main/java/com/best/ai_common/metrics/TokenUsageListener.java`

```java
package com.best.ai_common.metrics;

import com.best.ai_common.context.AiUserContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Instant;
import java.util.Map;

/**
 * token 用量统计。
 *
 * <p><b>为什么必须有 onRequest：</b>
 * {@code onResponse} 里<b>不能</b>读 {@link AiUserContext} —— 它是 ThreadLocal，
 * 而 onResponse 的回调在<b>模型自己的 I/O 线程</b>上执行（流式场景下是 OkHttp 的线程），
 * 读出来永远是 null。实测 LangChain4j 1.20 的 {@code StreamingChatModel.chat(...)}：
 * 它是接口的 default 方法，<b>先</b>触发 onRequest、<b>再</b>调 doChat 发起异步 HTTP ——
 * 所以 onRequest 是在<b>调用线程</b>上执行的，那里读得到。
 *
 * <p>据此的规则是：<b>onRequest 里快照，onResponse 里从 attributes 取。</b>
 * attributes 是框架创建的 {@code ConcurrentHashMap}，同一个实例在
 * onRequest / onResponse / onError 之间传递，跨线程读写安全。
 *
 * <p><b>流式的 usage 能不能拿到：</b>
 * LangChain4j 1.20 的 {@code OpenAiStreamingChatModel} 已经自动带上了
 * {@code stream_options: {include_usage: true}}，所以只要服务端返回 usage，
 * {@code chatResponse().tokenUsage()} 就有值。拿不到时本类会直接 return（见下）——
 * 表现为台账里流式记录数偏少或为零，是静默的，排查方向是服务端有没有认这个参数。
 */
public class TokenUsageListener implements ChatModelListener {

    /** attributes 的 key：发起调用时快照下来的 userId */
    private static final String ATTR_USER_ID = "ai.user.id";

    /** 来源服务名，多服务共用一张表时用于按服务统计 */
    private final String serviceName;

    private final String profile;

    private final String modelName;

    private final TokenUsageRecorder recorder;

    public TokenUsageListener(String serviceName, String profile, String modelName, TokenUsageRecorder recorder) {
        this.serviceName = serviceName;
        this.profile = profile;
        this.modelName = modelName;
        this.recorder = recorder;
    }

    /**
     * ★ 整个类里最关键的一个方法 —— 它同时修好了同步和流式两条路径。
     *
     * <p>本方法在调用线程上执行（框架保证，见类注释），所以这里是
     * <b>唯一</b>能读到 {@link AiUserContext} 的时机。读到之后放进 attributes，
     * onResponse 就不必再碰 ThreadLocal。
     */
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        attributes.put(ATTR_USER_ID, AiUserContext.get());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        TokenUsage tokenUsage = responseContext.chatResponse().tokenUsage();
        if (tokenUsage == null) {
            // 服务端没返回 usage。不要在这里补一个 0 —— 0 会被当成"这次调用没花 token"，
            // 而事实是"不知道"。留空才有机会被发现。
            return;
        }
        recorder.record(new TokenUsageRecord(
                userIdOf(responseContext.attributes()),
                serviceName,
                profile,
                modelName,
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount(),
                Instant.now()
        ));
    }

    /**
     * 失败也要记一笔 —— 否则"失败率"这个指标无从统计，而且额度被消耗却查不到原因。
     *
     * <p>注意 token 数只能给 null：请求没走完，服务端不会返回用量。
     * 表里这几个列本来就可空，所以能存。
     */
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        recorder.record(new TokenUsageRecord(
                userIdOf(errorContext.attributes()),
                serviceName,
                profile,
                modelName,
                null, null, null,
                Instant.now()
        ));
    }

    /**
     * 从 attributes 取 userId —— <b>不是</b>从 {@link AiUserContext} 取。
     * 只有 onRequest（调用线程）才读得到 ThreadLocal。
     */
    private static String userIdOf(Map<Object, Object> attributes) {
        Object userId = attributes.get(ATTR_USER_ID);
        return userId instanceof String s && !s.isBlank() ? s : AiUserContext.ANONYMOUS;
    }
}
```

### 验证方法

```bash
# 起服务，跑一次流式对话，然后查表 —— 这次 user_id 应该有真实值
curl -N "http://localhost:8080/chat/stream?userId=u1&sessionId=s1&message=你好" \
     -H "X-User-Id: u1"
```

```sql
SELECT id, user_id, service_name, profile, model_name,
       input_tokens, output_tokens, total_tokens
FROM ai_token_usage ORDER BY id DESC LIMIT 5;
```

**两种可能的结果：**

| 结果 | 含义 | 下一步 |
|---|---|---|
| `user_id=u1` + token 有数字 | 两条都通了 ✓ | 完成 |
| `user_id=u1` + token 为 `NULL` | **userId 修好了**，但服务端没返回 usage | 查网关/厂商有没有处理 `include_usage`（这是另一件事，与 userId 无关） |
| `user_id=anonymous` | `X-User-Id` 头没传，或 `AiUserContextInterceptor` 没生效 | 查请求头；注意 `AiUserContextInterceptor` 只在 servlet web 环境注册 |

> **`X-User-Id` 那一行不能省** —— 那是 `AiUserContext` 的唯一来源。
> 而它的存在正是 P1-9 那个安全问题的另一面（这个头可伪造），详见 15 号。

## 0.6 一个附带的发现：官方有"预置 attributes"的通道

字节码里那行 `new ConcurrentHashMap<>(options.listenerAttributes())` 说明：
`ChatRequestOptions` 有一个 `listenerAttributes()` Map，**可以在发起调用时预置 attributes**。

理论上这比 ThreadLocal 更干净（不用依赖"onRequest 在调用线程"这个行为）。但问题是
**`AiServices` 没有把这个通道暴露给业务层** —— `chat(ChatRequest, ChatRequestOptions, handler)`
这个重载是 `AiServices` 内部调的，我们插不进去。

**所以一期还是用 `onRequest` 快照。** 记录在这里是因为：如果将来 LangChain4j 给 `AiServices`
加了 `chatRequestOptions(...)` 的 provider，那会是更正统的做法。

---

# 第一节 · 其余待改文件的完整代码

## 1.1 `DbUserMemoryStore`（修 P0-3：删错数据）

路径：`ai-memory/src/main/java/com/best/ai_memory/user/db/DbUserMemoryStore.java`

```java
package com.best.ai_memory.user.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.best.ai_memory.user.UserMemory;
import com.best.ai_memory.user.UserMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * {@link UserMemoryStore} 的 MyBatis-Plus 实现。
 *
 * <p>换 ORM 只需要换这一个文件 —— {@code UserMemoryRecorder} 和
 * {@code MemoryRecallService} 一行都不用改。这就是接口存在的全部理由。
 */
@Slf4j
@RequiredArgsConstructor
public class DbUserMemoryStore implements UserMemoryStore {

    private final UserMemoryMapper userMemoryMapper;

    @Override
    public void insert(UserMemory memory) {
        // IdType.AUTO：insert 之后自增主键会【回填】到 memory.getId()。
        // 这是实体必须是 class 不能是 record 的直接原因。
        userMemoryMapper.insert(memory);
    }

    @Override
    public List<UserMemory> findByUserId(String userId, int limit) {
        return userMemoryMapper.selectByUserId(userId, limit);
    }

    @Override
    public int countByUserId(String userId) {
        // 方法引用是【编译期】校验的：字段名改错编译不过。
        // 字符串列名的写法要等运行时才发现。
        Long count = userMemoryMapper.selectCount(
                new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUserId, userId)
        );
        return count == null ? 0 : count.intValue();
    }

    /**
     * 删掉最老的几条，用于容量控制。
     *
     * <p>刻意拆成"先查 id 再按 id 删"两步，而不是写
     * {@code DELETE ... ORDER BY ... LIMIT ?} —— 后者是 MySQL 专有语法，
     * 在 H2 / PostgreSQL 上会直接报错。分两步之后这个实现是跨库可移植的。
     *
     * <p>代价是多一次查询。但这个方法只在用户记忆超上限时才被调到，频率极低。
     */
    @Override
    public void deleteOldest(String userId, int count) {
        if (count <= 0) {
            return;
        }

        // 第一步：只查出要删的【主键】
        List<Long> ids = userMemoryMapper.selectOldestIds(userId, count);
        if (ids.isEmpty()) {
            // 必须挡这一下：ids 为空时 Wrapper 会生成 IN ()，
            // 而 MySQL 的 IN () 是【语法错误】，不是"匹配零行"
            return;
        }

        // 第二步：按【主键】删。
        //
        // ⚠️ 这里曾经误写成 UserMemory::getUserId —— ids 里是主键，拿去匹配 user_id 列
        //    会出两种事：
        //      ① userId 是 UUID/工号时，MySQL 隐式类型转换后全为 0 → 一条都删不掉，
        //         容量控制静默失效，该用户的记忆无限增长；
        //      ② user_id 恰好等于某个 id 值时，那一行被误删（跨用户数据删除）。
        //    而且它潜伏得深：只有超过 maxFactsPerUser 才会走到这里。
        int deleted = userMemoryMapper.delete(
                new LambdaQueryWrapper<UserMemory>().in(UserMemory::getId, ids));

        // 第三步：核对影响行数。
        // "按主键删"是【可以精确核对】的（预期 = ids.size()），不核白不核 ——
        // 数量不符说明有并发删除或逻辑错，必须能看见。
        if (deleted != ids.size()) {
            log.warn("用户 {} 的旧记忆清理数量不符：预期 {}，实际 {}", userId, ids.size(), deleted);
        }
    }

    @Override
    public void deleteAll(String userId) {
        userMemoryMapper.delete(new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUserId, userId));
    }
}
```

> 变化三处：`getUserId` → **`getId`**（真 bug）、补影响行数核对、加 `@Slf4j`。
> 同时删掉原来那个没用到的 import `LambdaQueryChainWrapper`。

## 1.2 `UserMemoryRecorder`（P1-3 维度过滤 + 批量向量化）

路径：`ai-memory/src/main/java/com/best/ai_memory/user/UserMemoryRecorder.java`

```java
package com.best.ai_memory.user;

import com.best.ai_memory.config.AiMemoryProperties;
import com.best.ai_memory.session.SessionKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

/**
 * 长期记忆的写入编排：抽取 → 向量化 → 去重 → 落库 → 容量控制。
 *
 * <p>这个方法跑在<b>异步线程</b>上（由 {@link #recordAsync} 丢给 TaskExecutor），
 * 所以它的慢不该影响用户 —— 但也意味着它内部的失败用户感知不到，
 * 只能靠日志和指标发现。
 */
@Slf4j
public class UserMemoryRecorder {

    private final UserMemoryExtractor extractor;
    private final EmbeddingModel embeddingModel;
    private final UserMemoryStore userMemoryStore;
    private final AiMemoryProperties.User config;
    private final TaskExecutor taskExecutor;

    public UserMemoryRecorder(UserMemoryExtractor extractor,
                              EmbeddingModel embeddingModel,
                              UserMemoryStore userMemoryStore,
                              AiMemoryProperties.User config,
                              TaskExecutor taskExecutor) {
        this.extractor = extractor;
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
        this.config = config;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 异步入口 —— 业务侧（Controller 的 onCompleteResponse）只调这一个。
     *
     * @param memoryId     形如 {@code assistantId::userId::sessionId}，
     *                     三段都由它自己解析，调用方只传手上那一个 id
     * @param recentTurns  最近的对话。注意：现在调用方只传了本轮的两条，
     *                     而抽取质量依赖上下文（"就按这个来" 这种单看抽不出来）。
     *                     建议改传最近 4-6 条，见交付单 15 号 4.2
     */
    public void recordAsync(String memoryId, List<ChatMessage> recentTurns) {
        if (!Boolean.TRUE.equals(config.enabled())) {
            return;
        }
        if (memoryId == null || memoryId.isBlank() || recentTurns == null || recentTurns.isEmpty()) {
            return;
        }
        SessionKey key = SessionKey.parse(memoryId);
        taskExecutor.execute(() -> {
            try {
                record(key.assistantId(), key.userId(), key.sessionId(), recentTurns);
            } catch (Exception e) {
                log.error("记录用户记忆失败, userId={}", key.userId(), e);
            }
        });
    }

    /** 同步实现。测试可以直接调它，不用起线程池。 */
    public void record(String assistantId, String userId, String sessionId, List<ChatMessage> recentTurns) {
        List<String> facts = extractor.extract(recentTurns);
        if (facts.isEmpty()) {
            return;
        }

        // ① 一次批量算向量。
        //    embedding API 通常按【请求数】计费和限流，不是按 token ——
        //    逐条 embed 的话，5 条事实就是 5 次 HTTP。批量之后是 1 次。
        List<Embedding> embeddings = batchEmbed(facts);
        if (embeddings.size() != facts.size()) {
            // 数量对不上就没法把向量和事实对应起来，宁可整批放弃
            log.error("向量化返回数量与事实数量不符：{} vs {}，本次跳过", embeddings.size(), facts.size());
            return;
        }

        List<UserMemory> existing = userMemoryStore.findByUserId(userId, config.maxFactsPerUser());
        int inserted = 0;
        for (int i = 0; i < facts.size(); i++) {
            String fact = facts.get(i);
            float[] vector = embeddings.get(i).vector();

            if (isDuplicate(vector, existing)) {
                log.debug("事实重复,跳过: {}", fact);
                continue;
            }

            UserMemory memory = UserMemory.of(assistantId, userId, fact, sessionId);
            // 实体是可变 class，向量直接 set 进去
            memory.setEmbedding(vector);
            userMemoryStore.insert(memory);

            // 插到头部：existing 是按 created_at DESC 拿出来的，
            // 新事实最新，所以放最前面，保持顺序语义
            existing.add(0, memory);
            inserted++;
        }

        if (inserted > 0) {
            enforceCapacity(userId);
            log.info("为用户 {} 新增 {} 条长期记忆", userId, inserted);
        }
    }

    /**
     * 判断候选事实是否与已有事实重复。
     *
     * @param candidate 候选事实的向量
     * @param existing  该用户已有的事实（按时间倒序）
     */
    private boolean isDuplicate(float[] candidate, List<UserMemory> existing) {
        if (existing.isEmpty()) {
            return false;
        }
        return existing.stream()
                .filter(m -> m.getEmbedding() != null && m.getEmbedding().length > 0)
                // ⚠️ 维度必须一致才能比。库里可能残留 dimensions 改过之前的老向量
                //    （比如 1024 维 vs 现在 1536 维），而 CosineSimilarity 对维度不一致
                //    是【抛异常】而不是返回 0 —— 不挡这一下，一条脏数据会让该用户的新事实
                //    全部写不进去，而且看起来只是"长期记忆不好使"。
                //    MemoryRecallService.isValid 那边做了同样的过滤，这里必须对称。
                .filter(m -> m.getEmbedding().length == candidate.length)
                .anyMatch(m -> CosineSimilarity.between(candidate, m.getEmbedding())
                        >= config.dedupThreshold());
    }

    /** 批量向量化。失败返回空列表，由调用方判数量不符后放弃。 */
    private List<Embedding> batchEmbed(List<String> facts) {
        try {
            List<TextSegment> segments = facts.stream().map(TextSegment::from).toList();
            return embeddingModel.embedAll(segments).content();
        } catch (Exception e) {
            log.error("批量向量化失败，本次跳过 {} 条事实", facts.size(), e);
            return List.of();
        }
    }

    /**
     * 容量控制：超过上限就删最老的。
     *
     * <p>在 insert 循环【之后】调用，所以 count 已经包含刚插入的。
     */
    private void enforceCapacity(String userId) {
        int count = userMemoryStore.countByUserId(userId);
        int overflow = count - config.maxFactsPerUser();
        if (overflow > 0) {
            userMemoryStore.deleteOldest(userId, overflow);
            log.warn("用户 {} 的长期记忆已达上限 {}，清理了最老的 {} 条",
                    userId, config.maxFactsPerUser(), overflow);
        }
    }
}
```

> 变化四处：① `isDuplicate` 加维度过滤（P1-3）；② 逐条 `embed` → `embedAll`（P2-4.1）；
> ③ `batchEmbed` 加了数量核对；④ 补注释。
>
> **注意 `Embedding.vector()`** —— `embedAll` 返回 `Response<List<Embedding>>`，
> 所以是 `.content()` 拿 `List<Embedding>`，再 `.get(i).vector()` 拿 `float[]`。

## 1.3 `ModelProperties`（P1-4：null 守卫 + 报错文案）

路径：`ai-common/src/main/java/com/best/ai_common/properties/ModelProperties.java`

```java
package com.best.ai_common.properties;

import java.util.Map;
import java.util.Set;

/**
 * 按 profile 组织的模型配置。
 *
 * @param <E> 端点类型，必须实现 IEndpoint
 */
public interface ModelProperties<E extends IEndpoint> {

    /** 默认走哪个 profile。允许为 null —— 由 ModelRegister 给出清晰的报错。 */
    String profile();

    /** profile 名 -> 端点配置 */
    Map<String, E> endpoints();

    /**
     * 取指定 profile 的端点。找不到就 fail fast，不要返回 null ——
     * 配置错误应该在启动阶段就暴露，而不是等第一个请求。
     *
     * <p>这里必须同时判 {@code null} 和 {@code isEmpty()}：
     * 如果配置里整段 {@code ai.chat.endpoints} 漏了，绑定结果可能是 {@code null}，
     * 而 {@code null.isEmpty()} 会抛 NPE —— 报错信息完全看不出是配置问题。
     * （ChatModelProperties 已经补了 {@code @DefaultValue} 兜底，这里是第二道防线。）
     */
    default E getEndpoint(String profile) {
        Map<String, E> endpoints = endpoints();
        if (endpoints == null || endpoints.isEmpty() || !endpoints.containsKey(profile)) {
            throw new IllegalArgumentException(
                    "未找到指定的模型配置: " + profile
                            + "，已配置的有 " + (endpoints == null ? Set.of() : endpoints.keySet()));
        }
        return endpoints.get(profile);
    }
}
```

> 变化三处：① 补 `endpoints == null` 判断；② 文案「制定」→「指定」；
> ③ 把已配置的 key 打出来（排查快十倍）。

## 1.4 `ChatModelProperties`（P1-4：补 `@DefaultValue` + 清 import）

路径：`ai-common/src/main/java/com/best/ai_common/properties/ChatModelProperties.java`

```java
package com.best.ai_common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * 对话模型配置。
 *
 * <p>三层参数覆盖顺序：端点级 &gt; 全局默认 &gt; @DefaultValue。
 * 全局字段永远不为 null（有 @DefaultValue 兜底），所以 create 里的
 * "endpoint 非空则用 endpoint，否则用全局"是必要且正确的。
 */
@ConfigurationProperties("ai.chat")
public record ChatModelProperties(
        /** 默认 profile 名，如 free。不设默认值 —— 缺省时由 ModelRegister 给出可读的报错 */
        String profile,

        @DefaultValue("0.7")  Double   temperature,
        @DefaultValue("2000") Integer  maxTokens,
        @DefaultValue("60s")  Duration timeout,

        /**
         * 缺失时绑定为空 Map，避免 getEndpoint 在 null 上 NPE。
         *
         * <p>⚠️ 这个 @DefaultValue 原来漏了（EmbeddingModelProperties 有、这里没有），
         * 导致漏配 ai.chat.endpoints 时得到的是 NPE 而不是可读的配置错误。
         */
        @DefaultValue Map<String, Endpoint> endpoints
) implements ModelProperties<ChatModelProperties.Endpoint> {

    public record Endpoint(
            String   baseUrl,
            String   apiKey,
            String   modelName,
            Double   temperature,
            Integer  maxTokens,
            Duration timeout
    ) implements IEndpoint {}
}
```

> 变化两处：`endpoints` 加 `@DefaultValue`；删掉两个没用到的 import
> （`DurationUnit`、`ChronoUnit`）。

## 1.5 `LlmConversationSummarizer`（P1-5 加固 + 摘要长度 + 文案）

路径：`ai-memory/src/main/java/com/best/ai_memory/session/summarizer/LlmConversationSummarizer.java`

```java
package com.best.ai_memory.session.summarizer;

import com.best.ai_memory.session.summary_store.SessionSummaryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 用 LLM 生成会话摘要。
 *
 * <p>它只收一个 {@link ChatModel}，不认识 ChatModelRegister ——
 * "用哪一档"的决定权在装配层（见交付单 04 第三节的统一规则）。
 *
 * <p><b>注意 append 是同步的</b>：{@code SummarizingChatMemory.add()} 在窗口溢出时
 * 直接调它，所以第 21 轮（窗口刚满）那次请求要等两次模型调用 ——
 * 摘要一次、主对话一次。用户会觉得"这轮特别慢"，这是设计使然不是 bug。
 */
@Slf4j
public class LlmConversationSummarizer implements ConversationSummarizer {

    private static final String PROMPT = """
           你是对话摘要器。下面给了【已有摘要】和【新增对话】。

           请把两者合并成一段新摘要，规则：
           1. 保留事实：人名、数字、决定、偏好、未完成的事项
           2. 丢弃寒暄、客套、重复内容
           3. 用第三人称陈述，不要写"用户说""助手回答"这类转述
           4. 不要分点，写成一到两段连续的话
           5. 只输出摘要正文，不要标题、不要解释
           6. 合并后的总长控制在 300 字以内。宁可漏掉细节，也要压住长度 ——
              这段摘要每一轮都会被带进上下文，它会一直跟着这个会话，
              不设上限的话它会一轮比一轮长，最后变成新的负担

           【已有摘要】
           %s

           【新增对话】
           %s
           """;

    private final ChatModel chatModel;
    private final SessionSummaryStore summaryStore;

    public LlmConversationSummarizer(ChatModel chatModel, SessionSummaryStore summaryStore) {
        this.chatModel = chatModel;
        this.summaryStore = summaryStore;
    }

    /**
     * 把 evicted 这批消息并入 memoryId 这个会话的摘要。
     *
     * <p>语义是<b>累积</b>：读旧摘要 + 新滑出的对话 → 重新生成 → 覆盖。
     * 不能改成"把新消息追加到旧摘要后面" —— 那样会无限增长，
     * 最后又变成一个放不下的上下文，摘要就失去意义了。
     */
    @Override
    public void append(Object memoryId, List<ChatMessage> evicted) {
        if (evicted == null || evicted.isEmpty()) {
            return;
        }
        try {
            String previous = summaryStore.get(memoryId);
            String prompt = PROMPT.formatted(
                    previous == null ? "(无)" : previous,
                    render(evicted)
            );
            String summary = chatModel.chat(prompt);
            if (summary == null || summary.isBlank()) {
                // 空结果会抹掉已有摘要，宁可保留旧的
                log.warn("摘要在 {} 上生成了空结果，保留旧摘要", memoryId);
                return;
            }
            summaryStore.save(memoryId, summary.trim());
        } catch (Exception e) {
            // 摘要失败不能影响对话 —— 调用方在 add() 里，抛出去会让整轮请求失败
            log.error("生成会话摘要失败，memoryId={}，本条摘要丢失 {} 条消息",
                    memoryId, evicted.size(), e);
        }
    }

    /** 把消息列表渲染成纯文本。取不到文本的消息直接跳过。 */
    private static String render(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            String text = textOf(m);
            // 拼出"助手: null"这种行没有意义，还会污染摘要
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(roleOf(m)).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    private static String roleOf(ChatMessage m) {
        if (m instanceof UserMessage) return "用户";
        if (m instanceof AiMessage) return "助手";
        if (m instanceof SystemMessage) return "系统";
        return "其他";
    }

    /**
     * 把消息转成纯文本，取不到就返回 null。
     *
     * <p><b>必须 try-catch，不能只判 null</b>：
     * <ul>
     *   <li>{@code UserMessage.singleText()} 在消息含多个 content（图片+文字）时
     *       会抛 {@code IllegalStateException}，不是返回 null</li>
     *   <li>{@code AiMessage.text()} 在"只有工具调用、没有文本"的轮次上可能为 null</li>
     * </ul>
     * 这些消息没法进摘要，跳过比让整次摘要失败好 —— 摘要失败是<b>静默丢内容</b>。
     * 接 MCP 工具之后，"只有工具调用的 AiMessage"会成为常态，所以这段防护不是可选的。
     */
    private static String textOf(ChatMessage m) {
        try {
            if (m instanceof UserMessage um) return um.singleText();
            if (m instanceof AiMessage am) return am.text();
            if (m instanceof SystemMessage sm) return sm.text();
        } catch (Exception e) {
            log.warn("消息无法转成纯文本，已从摘要中跳过: {}", m.getClass().getSimpleName());
        }
        return null;
    }
}
```

> 变化四处：① 提示词加第 6 条（长度上限）；② `render` 跳过空文本；
> ③ `textOf` 加 try-catch（P1-5）；④ 日志「sessionId=」→「memoryId=」、「生产」→「生成」。

## 1.6 `LlmUserMemoryExtractor`（P1-5 加固 + P1-6 提示词）

路径：`ai-memory/src/main/java/com/best/ai_memory/user/LlmUserMemoryExtractor.java`

```java
package com.best.ai_memory.user;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 用 LLM 从对话里抽取"关于用户的、值得长期记住的"事实。
 *
 * <p>它只收一个 {@link ChatModel}（由装配层解析好的那一档），不认识 register。
 */
@Slf4j
public class LlmUserMemoryExtractor implements UserMemoryExtractor {

    /**
     * 结构化输出：让模型返回 JSON，再用 AiServices 映射成对象。
     * 比"让它输出纯文本再自己切分"稳得多 —— 后者遇到模型加个序号就废了。
     */
    public record ExtractionResult(List<Fact> memories) {
        public record Fact(String content) {}
    }

    interface ExtractorAssistant {
        @SystemMessage("""
                你从对话里抽取【关于用户本人的、值得长期记住的】信息。

                注意：抽出来的事实是【跨助手共享】的 —— 用户在客服里说的话，
                运筹眼助手也会看到。所以判断标准里多一条：
                换个场景、换个助手，这句话还成立吗？不成立就别抽。

                该抽的：
                - 身份与角色（部门、岗位、职责范围）
                - 稳定的偏好（喜欢什么形式的输出、习惯用什么工具）
                - 明确说过的长期目标、约束、决策
                - 用户自己确认过的事实

                不该抽的：
                - 一次性的问题（"帮我查一下上周的数据"）
                - 只对本次任务成立的上下文（"这次用表格"、"这个订单"、"刚才那个报表"）
                - 助手说的话（只抽用户的信息）
                - 寒暄、客套、情绪
                - 临时的状态（"我现在有点忙"）
                - 从对话里推断出来的、用户没说过的结论

                每条写成一句话，以「用户」开头，不要分点、不要编号。

                示例一
                对话：
                用户：我们部门是做经营分析的，我负责月度报表这块
                助手：好的，那我以后…
                输出：{"memories":[{"content":"用户在经营分析部门，负责月度报表"}]}

                示例二
                对话：
                用户：帮我查一下昨天的 GMV
                助手：昨天 GMV 是…
                输出：{"memories":[]}

                示例三
                对话：
                用户：以后给我结果都直接上表格，别写大段文字
                助手：明白
                输出：{"memories":[{"content":"用户偏好表格形式的输出，不喜欢大段文字"}]}

                如果没有任何值得记的，返回 {"memories":[]}。
                """)
        ExtractionResult extract(@dev.langchain4j.service.UserMessage String conversation);
    }

    private final ExtractorAssistant assistant;

    public LlmUserMemoryExtractor(ChatModel chatModel) {
        this.assistant = AiServices.builder(ExtractorAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    @Override
    public List<String> extract(List<ChatMessage> recentTurns) {
        if (recentTurns == null || recentTurns.isEmpty()) {
            return List.of();
        }
        try {
            ExtractionResult result = assistant.extract(render(recentTurns));
            if (result == null || result.memories() == null) {
                return List.of();
            }
            return result.memories().stream()
                    .map(ExtractionResult.Fact::content)
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (Exception e) {
            // 抽取失败只影响"这次没记住"，不该影响对话本身
            log.error("抽取用户事实失败，本次跳过", e);
            return List.of();
        }
    }

    private static String render(List<ChatMessage> turns) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : turns) {
            // 只抽用户说的 + 助手的回复，其它类型（工具结果等）不进提示词
            boolean isUser = m instanceof UserMessage;
            if (!isUser && !(m instanceof AiMessage)) {
                continue;
            }
            String text = textOf(m);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(isUser ? "用户：" : "助手：").append(text).append('\n');
        }
        return sb.toString();
    }

    /**
     * 见 {@code LlmConversationSummarizer.textOf} 的说明：
     * {@code singleText()} 在多模态消息上会抛异常，不能只判 null。
     * 而接 MCP 工具之后，"只有工具调用的 AiMessage"会成为常态。
     */
    private static String textOf(ChatMessage m) {
        try {
            if (m instanceof UserMessage um) return um.singleText();
            if (m instanceof AiMessage am) return am.text();
        } catch (Exception e) {
            log.warn("消息无法转成纯文本，已跳过: {}", m.getClass().getSimpleName());
        }
        return null;
    }
}
```

> 变化三处：① 提示词补「跨助手共享」判据 + 「只对本次任务成立」条目（P1-6）；
> ② `render` 重构（跳过空文本、显式跳过非 user/ai 消息）；③ 加 `textOf` 的 try-catch（P1-5）。
> 同时删掉了没用到的 `ChatModelRegister` import。

## 1.7 `RedisSessionMemoryStore`（P1-7 降级 + 清 import）

路径：`ai-memory/src/main/java/com/best/ai_memory/session/memory_store/RedisSessionMemoryStore.java`

```java
package com.best.ai_memory.session.memory_store;

import com.best.ai_memory.session.SessionRedisKey;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史的 Redis 存储。
 *
 * <p><b>TTL 是"滑动过期"语义</b>：每次 {@link #updateMessages} 都会重置 ttl，
 * 所以只要用户还在聊，历史就不过期。这和 "ttl: 48h → 48 小时后消失" 的直觉不一样，
 * 但正是我们要的（活跃会话不该被中途清掉）。
 *
 * <p>消息和摘要的 key 由 {@link SessionRedisKey} 统一构造 ——
 * userId 走 SHA-256，assistantId 和 sessionId 明文。两边必须同一套规则，
 * 否则会出现"消息按 A 规则清、摘要按 B 规则找"永远对不上的状态。
 */
@Slf4j
public class RedisSessionMemoryStore implements SessionMemoryStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionMemoryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = keyOf(memoryId);
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
        } catch (Exception e) {
            // ⚠️ 脏数据（格式变过 / 手工写入 / LangChain4j 版本不兼容）不能让整轮对话失败 ——
            //    这个方法【每轮都会被调用】（SummarizingChatMemory.messages()），
            //    抛出去等于这个会话永久不可用，而且【无法自愈】，只能人工去 Redis 删 key。
            //    清掉它，让会话从"空历史"重新开始：丢一次上下文，比彻底不能用好。
            log.error("会话历史反序列化失败，已清空该会话: key={}", key, e);
            redis.delete(key);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            redis.delete(keyOf(memoryId));
            return;
        }
        redis.opsForValue().set(
                keyOf(memoryId),
                ChatMessageSerializer.messagesToJson(messages),
                ttl
        );
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    private String keyOf(Object memoryId) {
        return SessionRedisKey.of(SessionRedisKey.MESSAGE_PREFIX, memoryId);
    }
}
```

> 变化两处：① `getMessages` 加 try-catch 降级（P1-7）；② 删掉 5 个没用到的 import
> （`StandardCharsets` / `MessageDigest` / `NoSuchAlgorithmException` / `HexFormat`，
> 以及 `@Slf4j` 本来没用 —— 现在用上了）。

## 1.8 `InMemorySessionMemoryStore`（P2：返回可变集合）

路径：`ai-memory/src/main/java/com/best/ai_memory/session/memory_store/InMemorySessionMemoryStore.java`

```java
package com.best.ai_memory.session.memory_store;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史的内存存储 —— 进程内、重启即丢。只适合本地跑通和单元测试。
 *
 * <p>它没有容量上限也没有过期，长时间运行会一直涨。生产用 Redis 版。
 */
public class InMemorySessionMemoryStore implements SessionMemoryStore {

    private final Map<Object, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> stored = store.get(memoryId);
        if (stored == null) {
            // 不用 List.of()：ChatMemoryStore 的契约要求返回【可变】副本
            //（LangChain4j 拿到之后会直接 add 改它）。虽然当前调用方
            // SummarizingChatMemory 会再包一层，但契约不该靠调用方兜着 ——
            // 换个调用方（比如官方的 MessageWindowChatMemory）当场就炸。
            return new ArrayList<>();
        }
        // 必须返回副本：否则并发读的线程会看到"改了一半"的列表
        return new ArrayList<>(stored);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 存副本：调用方后续修改它传入的列表时不该影响这里
        store.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId);
    }
}
```

> 变化一处：`List.of()` → `new ArrayList<>()`。

## 1.9 `RedisSessionSummaryStore`（P2：save 判空）

路径：`ai-memory/src/main/java/com/best/ai_memory/session/summary_store/RedisSessionSummaryStore.java`

```java
package com.best.ai_memory.session.summary_store;

import com.best.ai_memory.session.SessionRedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 会话摘要的 Redis 存储。
 *
 * <p>参数名是 memoryId 不是 sessionId —— 收到的是 {@code ChatMemory.id()}，
 * 也就是 {@code assistantId::userId::sessionId} 这个完整标识。
 * （早期这里叫 sessionId，导致实现里放心地把它直接拼进 key，明文 userId 就进了 Redis。）
 */
public class RedisSessionSummaryStore implements SessionSummaryStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionSummaryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public String get(Object memoryId) {
        return redis.opsForValue().get(keyOf(memoryId));
    }

    @Override
    public void save(Object memoryId, String summary) {
        if (summary == null || summary.isBlank()) {
            // 空摘要没有意义，且 Redis 不接受 null value（会抛 IllegalArgumentException）。
            // 删掉旧值而不是直接 return —— 后者会留下上一轮的摘要，
            // 让调用方以为新的已经写进去了。
            delete(memoryId);
            return;
        }
        // 每次写入续期，和消息的 TTL 是同一个语义（滑动过期）
        redis.opsForValue().set(keyOf(memoryId), summary, ttl);
    }

    @Override
    public void delete(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    private String keyOf(Object memoryId) {
        return SessionRedisKey.of(SessionRedisKey.SUMMARY_PREFIX, memoryId);
    }
}
```

> 变化一处：`save` 补空值守卫。

## 1.10 `ChatAssistantTest`（P0-4）

完整代码见 `14-代码审核-第二轮.md` 的 P0-4 节（那里有详细的注释解释）。
这里只重复一句：**它必须整体替换**，因为现在的版本 `memoryId = "u1"` 会直接抛异常。

要点回顾：
- memoryId 必须三段：`assistant-demo::u1::s1`
- 用 `MessageCountingChatModel` 假模型，**不联网、不需要 MySQL / Redis**
- 三个结构断言：同会话能看到历史 / 不同会话隔离 / 不同助手隔离

---

# 第二节 · 配置与 pom

## 2.1 `assistant-demo/src/main/resources/application.yml`（P1-8）

**整个文件替换**（只有 `ai.embed.timeout` 和两个注释变了）：

```yaml
server:
  port: 8080

spring:
  application:
    name: assistant-demo

  datasource:
    url: jdbc:mysql://localhost:3306/mcp_gateway?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

  data:
    redis:
      host: localhost
      port: 6379

  # ⚠️ 必须 >= SseEmitter 的超时（控制器里是 120_000L），否则长回答会被容器掐断。
  #    两侧不一致的表现为"AI 说到一半突然断了"，而且不报错。
  mvc:
    async:
      request-timeout: 120s

ai:
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

  embed:
    profile: default
    dimensions: 1536
    # ⚠️ 这个值【同时管】写入和检索，而两者的要求完全不同：
    #      · 写入（抽取后算向量）：异步线程，慢一点无所谓
    #      · 检索（每轮对话召回事实）：在请求路径上【同步】执行，
    #        而且是在调主模型【之前】—— 这里超时多久，用户就干等多久
    #    embedding 正常 1-2 秒返回。设 5 秒的语义是"超过 5 秒就不值得等，
    #    直接放弃长期记忆、正常回答"。60s 是照抄 chat 的值，对检索太长。
    timeout: 5s
    max-retries: 3
    endpoints:
      default:
        base-url: ${CLOSEAI_BASE_URL}
        api-key: ${CLOSEAI_API_KEY}
        model-name: text-embedding-small-3

  metrics:
    service-name: ${spring.application.name}

  memory:
    assistant-id: ${spring.application.name}

    session:
      store: redis
      max-messages: 20
      summary-enabled: true
      summary-ttl: 96h      # ⚠️ 必须 >= ttl
      ttl: 48h               # 滑动过期：每次写入续期，活跃会话不会中途消失

    user:
      enabled: true
      top-k: 5
      min-score: 0.60
      extract-profile: free  # ⚠️ 必须指向 ai.chat.endpoints 里【真实存在】的档位，
                             #    否则第一次抽取时才炸（在异步线程里，只留一行 error 日志）
      dedup-threshold: 0.95
      max-facts-per-user: 500
```

## 2.2 `ai-common/pom.xml`（P2-4.4）

两处加 `<optional>true</optional>`：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <!-- 只为 AiUserContextInterceptor 编译。标 optional 之后不传递给使用者 ——
                 否则一个非 web 的服务引 ai-memory 会被拖上整个 Tomcat。 -->
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <!-- 只在编译期生成配置元数据，不该传递给使用者 -->
            <optional>true</optional>
        </dependency>
```

## 2.3 `ai-memory/pom.xml`（P2-4.4）

加一个依赖（生成 `ai.memory.*` 的 IDE 补全与校验）：

```xml
        <!-- 生成配置元数据，让 IDE 对 ai.memory.* 有补全和校验。
             没有它的话 extract-profile 写成 extract-profiles 时 IDE 一言不发。 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
```

同时把 redis starter 标 optional（这条从交付单 10 号就挂着）：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
            <!-- 标 optional：只在 store=redis 时才需要。
                 否则任何引 ai-memory 的服务都会被拖上 Lettuce + Redis 自动配置，
                 一个只用内存版的服务跑在没有 Redis 的环境会启动失败。
                 AiMemoryAutoConfiguration 里的 @ConditionalOnClass(StringRedisTemplate.class)
                 已经能正确跳过。 -->
            <optional>true</optional>
        </dependency>
```

> ⚠️ **标了 optional 之后，`assistant-demo` 要显式引 redis starter**（它已经引了 ✓）。
> 这正是 optional 的本意：把"要不要 Redis"的决定权交给使用方。

## 2.4 `assistant-demo/pom.xml`（P0-2 的连带）

如果你想用 `spring-boot:run` 或打可执行 jar，**加一个 plugin**（现在没有）：

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
```

---

# 第三节 · 不要动的

| 文件 / 项 | 为什么 |
|---|---|
| `register/ChatModelRegister.java` | 已经是正确形状，本文的模板 |
| `register/EmbeddingModelRegister.java` | 不涉 token 计量 |
| `config/AiMemoryAutoConfiguration.java` | 已是"1 个类 + 嵌套 Configuration"的正确形状 |
| `SessionKey` / `SessionRedisKey` | 已修好，不动 |
| `UserMemory` / `UserMemoryMapper` / `UserMemoryMapper.xml` | 上一轮已同步 |
| `AiTokenUsageMapper.xml` | 没引用 entity 全限定名，不用改 |
| `AiUserContext` / `AiUserContextInterceptor` | P2 风格问题（一个类干两件事），**建议不动** |
| `ModelRegister` 的 `DefaultProfile()` 命名 | 改它要动 5 处，**单独一次做**（别混进来） |
| `ModelRegister.get(profile)` 的 null 守卫 | 同上，和命名一起改 |
| 摘要的**代码**截断 | 长度控制放在提示词里（1.5 已做），代码截断会把句子切半截 |
| `SummarizingChatMemory` | 它的 read-modify-write 竞态只加注释，不改代码（见 15 号 5.1） |

**P1-9（`userId` 越权）不在本文** —— 它需要网关侧覆写 `X-User-Id`，属跨团队约定，
不是代码单方面能解决的。见 15 号 P1-9。

---

# 第四节 · 验证

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile && \
/opt/homebrew/bin/mvn -o -pl ai-memory test && \
/opt/homebrew/bin/mvn -o -pl assistant-demo test
```

**第三步不能省** —— `ChatAssistantTest` 改的就是它。预期 **3 个测试全绿**（不联网）。

然后起真应用，跑一次流式对话验证 token 台账：

```bash
curl -N "http://localhost:8080/chat/stream?userId=u1&sessionId=s1&message=我是财务部的" \
     -H "X-User-Id: u1"
```

```sql
SELECT id, user_id, service_name, profile, model_name,
       input_tokens, output_tokens, total_tokens
FROM ai_token_usage ORDER BY id DESC LIMIT 5;
```

**这次 `user_id` 应该是 `u1`**，不再是 `anonymous`。

---

## 一页速记

```
★ 流式 token 统计的正解（无需网关）
  证据：① OpenAiStreamingChatModel 第197行已自动带 include_usage:true
        ② StreamingChatModel.chat() 是 default 方法，先 onRequest 后 doChat → onRequest 在【调用线程】
        ③ attributes 是 ConcurrentHashMap，跨线程安全
  做法：TokenUsageListener 补 onRequest（快照 userId），onResponse 从 attributes 取
  改动：1 个文件、~15 行

其余改动（完整代码见正文）
  1.1  DbUserMemoryStore          getUserId → getId（删错数据的真 bug）+ 核对影响行数
  1.2  UserMemoryRecorder         isDuplicate 加维度过滤 + embedAll 批量向量化
  1.3  ModelProperties            getEndpoint 补 null 守卫 + 文案
  1.4  ChatModelProperties        endpoints 补 @DefaultValue + 清 2 个 import
  1.5  LlmConversationSummarizer  提示词加长度上限 + textOf 加 try-catch + 文案
  1.6  LlmUserMemoryExtractor     提示词补「跨助手共享」+ render 重构 + try-catch
  1.7  RedisSessionMemoryStore    反序列化失败降级 + 清 5 个 import
  1.8  InMemorySessionMemoryStore null 分支返回可变集合
  1.9  RedisSessionSummaryStore   save 补空值守卫
  1.10 ChatAssistantTest          整体替换（见 14 号）
  2.1  application.yml            ai.embed.timeout 60s → 5s
  2.2  ai-common/pom.xml          web starter + configuration-processor 标 optional
  2.3  ai-memory/pom.xml          加 configuration-processor；redis starter 标 optional
  2.4  assistant-demo/pom.xml     加 spring-boot-maven-plugin

验证：clean compile → ai-memory test → assistant-demo test → 起真应用查 ai_token_usage
```
