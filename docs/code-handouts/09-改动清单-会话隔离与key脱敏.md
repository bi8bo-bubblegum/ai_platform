# 改动清单 · 会话作用域隔离 + 摘要 key 脱敏

> 这份是「一次改完」用的，不是新知识。**下面每个代码块都是「改后的完整文件」，直接整文件替换即可。**
> 涉及 12 个文件：9 个改、3 个新增。改完跑 `mvn -pl ai-memory test` 应该是 **18 个测试全绿**（交付单 01+02 的范围）。

---

## 一、总表（先看这个）

| # | 文件 | 动作 | 一句话理由 |
|---|---|---|---|
| 1 | `ai-memory/.../session/SessionKey.java` | **改** | memoryId 从两段变三段，加 `assistantId` |
| 2 | `ai-memory/.../session/SessionRedisKey.java` | **新增** | Redis key 的唯一构造点 |
| 3 | `ai-memory/.../session/memory_store/RedisSessionMemoryStore.java` | **改** | key 改用它，并带上 assistantId |
| 4 | `ai-memory/.../session/summary_store/RedisSessionSummaryStore.java` | **改** | ⚠️ **修 bug：原来明文 userId 进了 Redis** |
| 5 | `ai-memory/.../session/summary_store/SessionSummaryStore.java` | **改** | 参数改名 `sessionId` → `memoryId` |
| 6 | `ai-memory/.../session/summary_store/InMemorySessionSummaryStore.java` | **改** | 同上 |
| 7 | `ai-memory/.../session/summarizer/ConversationSummarizer.java` | **改** | 同上 |
| 8 | `ai-memory/.../session/summarizer/NoOpConversationSummarizer.java` | **改** | 同上 |
| 9 | `ai-memory/.../session/summarizer/LlmConversationSummarizer.java` | **改** | 同上 + 一处日志文案 |
| 10 | `ai-memory/src/test/.../session/SessionKeyTest.java` | **改** | 原来两个测试只 `println`，没有任何断言 |
| 11 | `assistant-demo/.../config/AssistantConfig.java` | **改** | `falseB` 笔误 + 补上 `@Bean` |
| 12 | `assistant-demo/.../controller/ChatStreamController.java` | **新增** | 拼 memoryId 要带 assistantId（交付单 04） |

**不用改的**（确认过）：`SessionMemoryStore`、`InMemorySessionMemoryStore`、`SummarizingChatMemory`、
`SummarizingChatMemoryProvider`、`AiMemoryProperties`、`AiMemoryAutoConfiguration`。
它们要么只用 `memoryId` 原样传递，要么压根不碰这个格式。

---

## 二、`SessionKey` 加第三段（最重要的一个）

**改前**（你现在的）：`record SessionKey(String userId, String sessionId)`

**改后 → 整文件替换：**

```java
package com.best.ai_memory.session;

/**
 * 「一次会话」的完整标识：哪个助手 + 谁 + 哪一次。
 *
 * <p>为什么不直接用几个独立参数传？
 * 因为 LangChain4j 的 ChatMemoryProvider 和 ContentRetriever 都只认一个 id，
 * 我们没有地方挂后面的几个。所以把三段编码进一个字符串，在需要的地方解析回来。
 *
 * <p><b>为什么必须有 assistantId：</b>会话历史是【每个助手各一份】的。
 * 少了它，只要两个助手收到相同的 (userId, sessionId) —— 而 sessionId 由调用方生成，
 * 没有任何机制保证全局唯一 —— 它们就会落进同一个 Redis key，共享同一份历史。
 * 症状是：在客服里说的事，运筹眼"记得"；而且摘要会被两边的对话交替污染。
 *
 * <p>注意这和【长期记忆】的取舍正好相反：用户事实是用户级的、跨助手共享（那是刻意的）；
 * 会话上下文是助手级的、不该跨。两个作用域不能混。
 *
 * <p>用 record 而不是普通类：它是纯值对象，天然需要 equals / hashCode / toString，
 * 而且不可变，跨线程传不会有意外。
 */
public record SessionKey(String assistantId, String userId, String sessionId) {

    private static final String SEPARATOR = "::";

    /**
     * 紧凑构造器：把"格式约定"从注释变成"构造时就炸"。
     *
     * <p>前两段不允许含分隔符 —— 否则 memoryId 无法无歧义地解析回来。
     * 与其等到解析时才发现，不如在这里就拦下来。
     */
    public SessionKey {
        requireNoSeparator(assistantId, "assistantId");
        requireNoSeparator(userId, "userId");
        // sessionId 允许含 ::（它是最后一段，解析时不歧义），但不能为空 ——
        // 否则 toMemoryId() 会拼出 "a::b::null" 这种看着正常、实际永远查不到东西的 key
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
    }

    public static SessionKey of(String assistantId, String userId, String sessionId) {
        return new SessionKey(assistantId, userId, sessionId);
    }

    /**
     * 拼成 LangChain4j 能用的 memoryId。
     *
     * <p>为什么叫 toMemoryId 而不是 toString？
     * toString 是"给人看的"，语义上可以随时改格式；toMemoryId 是"给框架用的"，
     * 改它会让存量数据的 key 全部失效。名字分开，改的时候心里有数。
     */
    public String toMemoryId() {
        return assistantId + SEPARATOR + userId + SEPARATOR + sessionId;
    }

    /**
     * 从 memoryId 解析回来。
     *
     * <p>只找【前两个】分隔符：第三段往后全部归 sessionId。
     * sessionId 里带 :: 是允许的，所以不能用 split 切成三段。
     */
    public static SessionKey parse(String memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId 不能为 null");
        }
        int first = memoryId.indexOf(SEPARATOR);
        int second = first < 0 ? -1 : memoryId.indexOf(SEPARATOR, first + SEPARATOR.length());
        if (second < 0) {
            // 直接抛异常而不是给默认值：
            // 格式错了意味着上游装配错了，这种错必须立刻炸出来，
            // 而不是悄悄把用户 A 的记忆记到用户 B 头上。
            throw new IllegalArgumentException(
                    "memoryId 格式必须是 assistantId" + SEPARATOR + "userId" + SEPARATOR
                            + "sessionId，实际收到: " + memoryId);
        }
        return new SessionKey(
                memoryId.substring(0, first),
                memoryId.substring(first + SEPARATOR.length(), second),
                memoryId.substring(second + SEPARATOR.length()));
    }

    private static void requireNoSeparator(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    name + " 不能包含 " + SEPARATOR + "，否则 memoryId 无法解析，实际: " + value);
        }
    }
}
```

---

## 三、新增 `SessionRedisKey`（key 的唯一构造点）

**路径**：`ai-memory/src/main/java/com/best/ai_memory/session/SessionRedisKey.java`

```java
package com.best.ai_memory.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * memoryId → Redis key 的【唯一】构造点。
 *
 * <p>为什么必须集中：会话消息和会话摘要存两个不同的 key 空间，但它们的"身份部分"
 * 必须完全一致 —— 否则会出现"消息删了、摘要还挂在"这种对不上的状态。
 * 曾经两处各写一遍拼接逻辑，摘要那一处就漏了脱敏（明文 userId 进了 Redis）。
 *
 * <p>key 的形状：{@code <prefix><assistantId>:<sha256(userId)>:<sessionId>}
 * <ul>
 *   <li>assistantId 明文 —— 不是个人信息，且排查时"这个 key 属于哪个助手"最常被问</li>
 *   <li>userId 走 SHA-256 —— 它有隐私风险，而 Redis key 会出现在
 *       MONITOR / SLOWLOG / RDB 里</li>
 *   <li>sessionId 明文 —— 我们自己生成的不透明 id，保留它排查省很多时间</li>
 * </ul>
 *
 * <p>用单冒号分隔，和 memoryId 的双冒号区分开：一眼能看出哪个是 key、哪个是 memoryId。
 */
public final class SessionRedisKey {

    /** 会话消息的 key 前缀 */
    public static final String MESSAGE_PREFIX = "ai.mem.session:";

    /** 会话摘要的 key 前缀 */
    public static final String SUMMARY_PREFIX = "ai.mem.summary:";

    private SessionRedisKey() {}

    /**
     * @param memoryId 形如 {@code assistantId::userId::sessionId}。格式不对会抛异常 ——
     *                 宁可炸，也不能退回把未脱敏的串写进 Redis。
     */
    public static String of(String prefix, Object memoryId) {
        SessionKey key = SessionKey.parse(String.valueOf(memoryId));
        return prefix
                + key.assistantId() + ':'
                + sha256(key.userId()) + ':'
                + key.sessionId();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // 只取前 8 字节（16 个十六进制字符）就够了 —— 我们要的是"不可逆"，
            // 不是密码学强度。完整的 64 字符会让 key 长得没法看。
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，正常走不到这里。
            // 真走到了，宁可整个流程失败，也不能退回明文 key。
            throw new IllegalStateException("JDK 不支持 SHA-256，无法生成安全 key", e);
        }
    }
}
```

---

## 四、`RedisSessionMemoryStore`（key 改用它）

**改后 → 整文件替换：**

```java
package com.best.ai_memory.session.memory_store;

import com.best.ai_memory.session.SessionRedisKey;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RedisSessionMemoryStore implements SessionMemoryStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSessionMemoryStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(keyOf(memoryId));

        // 没有 或 空串：都当作"这个会话还没开始"，返回空列表而不是 null
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        // 必须再包一层 ArrayList。
        // ChatMessageDeserializer 返回的列表，可变性没被承诺 ——
        // 而 LangChain4j 拿到之后会直接 .add()。包一层可变副本，把不确定性挡在这里。
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            // 空列表不写 Redis：存 "[]" 和一个不存在的 key 对读取方没区别，
            // 但存着会白占内存，还会让"这个会话是否还活着"的判断变复杂。
            redis.delete(keyOf(memoryId));
            return;
        }

        // 关键：每次写都用带 TTL 的 set，而不是先 set 再 expire。
        // 这带来一个很重要的性质 —— TTL 是"滑动"的：
        // 只要这个会话还在被使用，它就一直不过期；一旦用户 48 小时没说话，整段历史自动消失。
        // 这正是我们要的语义，不需要额外的清理任务。
        redis.opsForValue().set(
                keyOf(memoryId),
                ChatMessageSerializer.messagesToJson(messages),
                ttl);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    /**
     * 拼 Redis key。
     *
     * <p>脱敏和形状都由 {@link SessionRedisKey} 说了算 —— 这里只负责传前缀。
     * 关键点：userId 哈希、assistantId 和 sessionId 明文，三个维度都在 key 里。
     */
    private String keyOf(Object memoryId) {
        return SessionRedisKey.of(SessionRedisKey.MESSAGE_PREFIX, memoryId);
    }
}
```

> **删掉的东西**：`KEY_PREFIX` 常量、私有的 `sha256(...)` 方法，以及
> `StandardCharsets` / `MessageDigest` / `NoSuchAlgorithmException` / `HexFormat` 四个 import。
> 它们都搬进 `SessionRedisKey` 了。

---

## 五、⚠️ `RedisSessionSummaryStore`（这里有个真 bug）

**你现在的代码**：

```java
private static final String KEY_PREFIX = "ai.mem.summary:";

@Override
public String get(Object sessionId) {              // ← 名字叫 sessionId
    return redis.opsForValue().get(KEY_PREFIX + sessionId);   // ← 直接拼！
}
```

`sessionId` 这个参数实际收到的是 **memoryId**（`ChatMemory.id()`）。所以真实 key 是
`ai.mem.summary:u123::s456` —— **明文 userId 直接进了 Redis**。
交付单 03 用了整页讲"key 会出现在 MONITOR / SLOWLOG / RDB 里"，结果摘要这条路上毫无防护。

**根因不是疏忽，是命名**：参数叫 `sessionId`，看名字的人会以为它是"我们自己生成的不透明 id"，
于是放心拼进 key。**名字骗了实现者。**

**改后 → 整文件替换：**

```java
package com.best.ai_memory.session.summary_store;

import com.best.ai_memory.session.SessionRedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 会话摘要的 Redis 存储。
 *
 * <p><b>注意参数名是 memoryId 而不是 sessionId。</b>
 * 方法签名上收到的是 ChatMemory#id()，也就是 assistantId::userId::sessionId 这个 memoryId，
 * 不是裸的 sessionId。上一版把它叫 sessionId，于是实现里放心地直接拼进 key，
 * 明文 userId 就进了 Redis —— 名字和它承载的东西对不上，是那类 bug 的常见起点。
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
        redis.opsForValue().set(keyOf(memoryId), summary, ttl);
    }

    @Override
    public void delete(Object memoryId) {
        redis.delete(keyOf(memoryId));
    }

    /**
     * 和消息的 key 走同一套规则 —— 只是前缀不同。
     * 否则会出现"消息按 A 规则清、摘要按 B 规则找"，永远对不上。
     */
    private String keyOf(Object memoryId) {
        return SessionRedisKey.of(SessionRedisKey.SUMMARY_PREFIX, memoryId);
    }
}
```

---

## 六、四个「只改参数名」的文件

改动只有一处：`sessionId` → `memoryId`。**不改也不会错，但名字继续骗下一个人。**

```java
// ai-memory/.../session/summary_store/SessionSummaryStore.java
package com.best.ai_memory.session.summary_store;

public interface SessionSummaryStore {

    String get(Object memoryId);

    void save(Object memoryId, String summary);

    void delete(Object memoryId);
}
```

```java
// ai-memory/.../session/summary_store/InMemorySessionSummaryStore.java
package com.best.ai_memory.session.summary_store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionSummaryStore implements SessionSummaryStore {

    private final Map<Object, String> store = new ConcurrentHashMap<>();

    @Override
    public String get(Object memoryId) {
        return store.get(memoryId);
    }

    @Override
    public void save(Object memoryId, String summary) {
        store.put(memoryId, summary);
    }

    @Override
    public void delete(Object memoryId) {
        store.remove(memoryId);
    }
}
```

```java
// ai-memory/.../session/summarizer/ConversationSummarizer.java
package com.best.ai_memory.session.summarizer;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface ConversationSummarizer {

    /**
     * 把 evicted 这批消息并入 memoryId 这个会话的摘要。
     *
     * @param memoryId 会话标识，就是 ChatMemory.id()，形如 assistantId::userId::sessionId
     * @param evicted  刚刚滑出窗口的消息，按时间正序
     */
    void append(Object memoryId, List<ChatMessage> evicted);
}
```

```java
// ai-memory/.../session/summarizer/NoOpConversationSummarizer.java
package com.best.ai_memory.session.summarizer;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public class NoOpConversationSummarizer implements ConversationSummarizer {

    @Override
    public void append(Object memoryId, List<ChatMessage> evicted) {
        // 故意什么都不做。后果是滑出窗口的消息被永久丢弃 —— 这正是"关掉摘要"的应有意为。
    }
}
```

---

## 七、`LlmConversationSummarizer`（改参数名 + 一处日志）

相对你现在这份，只有三处不同：`append` 的参数名、方法体内两个 `sessionId`
（`summaryStore.get(...)` / `summaryStore.save(...)`）、以及 `log.warn` / `log.error` 里的文案。

```java
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
                log.warn("摘要在{}上生成了空结果，保留旧摘要", memoryId);   // 原来这里写的是"生产了"，顺手改了
                return;
            }
            summaryStore.save(memoryId, summary.trim());
        } catch (Exception e) {
            log.error("生成会话摘要失败，memoryId={}，本条摘要丢失 {} 条消息",
                    memoryId, evicted.size(), e);
        }
    }
```

> 其余部分（`PROMPT`、`render`、`roleOf`、`textOf`、构造器）**一行都不用动**。

---

## 八、重写 `SessionKeyTest`

你原来那两个测试只有 `System.out.println`，**没有任何断言** —— 跑过等于没跑。
下面这 5 个是**结构断言**：格式一变就红。

**路径**：`ai-memory/src/test/java/com/best/ai_memory/session/SessionKeyTest.java`

```java
package com.best.ai_memory.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionKeyTest {

    @Test
    @DisplayName("拼出来再解析回去，内容一模一样")
    void roundTrip() {
        SessionKey key = SessionKey.of("assistant-demo", "u123", "s456");

        assertThat(key.toMemoryId()).isEqualTo("assistant-demo::u123::s456");
        assertThat(SessionKey.parse("assistant-demo::u123::s456")).isEqualTo(key);
    }

    @Test
    @DisplayName("只分开前两段，后面全部归 sessionId")
    void onlyFirstTwoSeparatorsSplit() {
        // 这条固化一个设计决策：parse 用的是 indexOf 而不是 split。
        // 谁要是改成 split 切成三段，这个用例会红。
        SessionKey key = SessionKey.parse("assistant-demo::u123::s456::extra");

        assertThat(key.assistantId()).isEqualTo("assistant-demo");
        assertThat(key.userId()).isEqualTo("u123");
        assertThat(key.sessionId()).isEqualTo("s456::extra");
    }

    @Test
    @DisplayName("只有两段（老格式）必须抛异常，不能被静默解读")
    void legacyTwoSegmentFormatThrows() {
        // "u123::s456" 是加 assistantId 之前的格式。
        // 如果 parse 对它宽容，就会被读成 assistantId=u123, userId=s456 —— 一段都不对。
        assertThatThrownBy(() -> SessionKey.parse("u123::s456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistantId::userId::sessionId");
    }

    @Test
    @DisplayName("assistantId / userId 里带 :: 时，构造就炸 —— 不留到解析时才暴露")
    void segmentMustNotContainSeparator() {
        assertThatThrownBy(() -> SessionKey.of("a::b", "u1", "s1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistantId");

        assertThatThrownBy(() -> SessionKey.of("assistant-demo", "u::1", "s1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("null 也要抛 IllegalArgumentException，而不是 NPE")
    void nullThrows() {
        assertThatThrownBy(() -> SessionKey.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

> 这需要 `spring-boot-starter-test`（含 assertj）—— `ai-memory/pom.xml` 里已经有了。
> 测试从 4 个变 5 个，所以「01 + 02 累计」从 17 变 **18**。

---

## 九、`AssistantConfig`（你那边现在编译不过）

你现在的文件：

```java
@Configuration(proxyBeanMethods = falseB)   // ← 多打了一个 B
public class AssistantConfig {
}                                            // ← 而且是空的
```

两处都要改。完整版见交付单 04 的 4.3，这里只给要点：

```java
package com.best.assistant_demo.config;

import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_common.register.StreamingChatModelRegister;
import com.best.assistant_demo.assistant.ChatAssistant;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AssistantConfig {

    @Bean
    public ChatAssistant chatAssistant(ChatModelRegister chatModelRegister,
                                       StreamingChatModelRegister streamingRegister,
                                       ChatMemoryProvider chatMemoryProvider) {

        ChatModel chatModel = chatModelRegister.getDefault();
        StreamingChatModel streamingChatModel = streamingRegister.getDefault();

        return AiServices.builder(ChatAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)   // 漏了这行 → 助手不记得上一句，且不报错
                .build();
    }
}
```

---

## 十、`ChatStreamController` 里那一行

交付单 04 的控制器里，拼 memoryId 的地方改了：

```java
    /**
     * 这个助手是谁 —— 会话记忆的隔离维度之一。
     *
     * <p>不注入它的话，两个助手收到相同的 (userId, sessionId) 就会共用同一份会话历史。
     */
    @Value("${ai.memory.assistant-id:${spring.application.name:unknown}}")
    private String assistantId;

    // ...

    // 改前：String memoryId = SessionKey.of(userId, sessionId).toMemoryId();
    String memoryId = SessionKey.of(assistantId, userId, sessionId).toMemoryId();
```

---

## 十一、跑之前的两件小事

**① 本地把旧 key 清掉。** 新的 key 形状和老的不一样，老的读不到了（也不会报错，只是"历史突然没了"）。

```bash
redis-cli --scan --pattern 'ai*' | xargs -r redis-cli del
```

**② 验证顺序：**

```bash
mvn -q -pl ai-memory -am clean compile     # 先编译
mvn -pl ai-memory test                     # 期待 18 个测试全绿（01 的 6 + 02 的 12）
```

`18` 这个数不对就说明计数或测试有出入，别往下走。

---

## 十二、还没做、但字段已经留好的

- `ai_user_memory.source_assistant`（交付单 05 会建）—— 目前只**记录**，检索时**不过滤**，
  "运筹眼能看到客服抽出的事实"是靠抽取提示词约束的。真要按助手隔离时，是加一个 where 条件的事。
- `SessionRedisKey` 的 `sha256` **没加盐**。上生产前补一个固定盐（改一个方法即可）。
