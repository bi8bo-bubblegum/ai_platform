# 交付单 03 · Redis 会话记忆存储

> **本篇目标**：把 01 的内存实现换成 Redis 实现，让对话历史在重启和扩容后仍然存在。
> **前置**：交付单 01、02 已跑通（18 个测试绿）。

---

## 本篇解决的三个问题

1. **序列化**：`ChatMessage` 是有继承层次的接口（`UserMessage` / `AiMessage` / `SystemMessage` / …），直接丢给 Jackson 会丢类型信息。LangChain4j 自带了一套，不用自己写。
2. **TTL**：什么时候刷新、设多长。
3. **key 里放什么**：这里有个真实的隐私坑。

---

## 你会敲出什么

| # | 文件 | 动作 |
|---|---|---|
| 1 | `ai-memory/pom.xml` | 改（加两个依赖） |
| 2 | `session/SessionRedisKey.java` | 新建（**key 的唯一构造点**） |
| 3 | `session/RedisSessionMemoryStore.java` | 新建 |
| 4 | `session/RedisSessionSummaryStore.java` | 新建 |
| 5 | `test/.../session/RedisSessionMemoryStoreTest.java` | 新建 |

---

# 第一节 · 依赖

## 1.1 改 `ai-memory/pom.xml`

在 `<dependencies>` 里加：

```xml
        <!--
          Redis 客户端。用 optional 而不是普通依赖：
          AI-Memory 支持两种会话存储（内存 / Redis），
          只想用内存实现的模块不该被迫拉上 Lettuce。
          想用 Redis 的模块自己引 spring-boot-starter-data-redis 即可 ——
          反正助手模块本来就要连 Redis。
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
            <optional>true</optional>
        </dependency>

        <!--
          Testcontainers：测试时起一个真实 Redis 容器。
          版本由 spring-boot-dependencies 统一管理（Spring Boot 3.1+ 管了 Testcontainers BOM），
          所以不写 <version>。
        -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

> **需要本地装了 Docker / OrbStack / Colima**，Testcontainers 才能起容器。没装的话本篇的测试跑不了 —— 到时候先跳过测试、只做编译验证，装上 Docker 再回来跑。

---

# 第二节 · 序列化

## 先搞清一件事

`ChatMessage` 是个接口，有 `UserMessage` / `AiMessage` / `SystemMessage` / `ToolExecutionResultMessage` 四个实现。它们的字段各不相同。如果直接用 Jackson 序列化一个 `List<ChatMessage>`，反序列化时 Jackson 不知道该造哪个具体类——**会退化成 LinkedHashMap，运行时才炸**。

LangChain4j 在 `dev.langchain4j.data.message` 里给了一套现成的：

| 方法 | 作用 |
|---|---|
| `ChatMessageSerializer.messagesToJson(List<ChatMessage>)` | 序列化，会在 JSON 里带上 `"type"` 字段 |
| `ChatMessageDeserializer.messagesFromJson(String)` | 按 `"type"` 还原成正确的具体类 |

**不要自己写 Jackson 映射。** 这套东西的兼容性由 LangChain4j 自己保证，包括字段增删时的向后兼容。

---

# 第三节 · key 的设计（有个坑）

## 先说坑

最直觉的写法是：

```java
String key = "ai:mem:session:" + userId + "::" + sessionId;
```

**这在生产环境是个隐私问题。** Redis 的 key 会出现在这些地方：

- `MONITOR` 命令的实时输出（运维排查时会开）
- `SLOWLOG` 的日志里
- RDB / AOF 持久化文件（备份、迁移时会被拷走）
- Redis 自带的 `SCAN` 结果（任何有权限的客户端都能列出来）

也就是说，**未脱敏的 userId 会散落到一堆"你以为不是数据区"的地方**。而 userId 通常是手机号、工号、邮箱这类可以直接定位到人的标识。

## 怎么办

三个选择，你按合规要求挑：

| 方案 | key 长相 | 取舍 |
|---|---|---|
| A. 明文放 | `ai.mem.session:yunchou:u123:s456` | 排查最方便，**有合规风险** |
| B. 只哈希 userId | `ai.mem.session:yunchou:a1b2c3d4e5f60718:s456` | 保留 assistantId 和 sessionId 明文，排查还行 |
| C. 整体哈希 | `ai.mem.session:a1b2c3d4e5f60718` | 最安全，但出事后根本认不出是哪个会话 |

本篇的代码实现 **B**：`assistantId` 和 `sessionId` 保持明文，`userId` 走 SHA-256。

理由：`assistantId` 不是个人信息，而且排查时"这个 key 属于哪个助手"几乎每次都要问；
`sessionId` 是我们自己生成的不透明 id，也不带个人信息；
只有 `userId` 有隐私风险（手机号 / 工号 / 邮箱），所以只有它需要脱敏。

### key 的形状里还有两件事

**一、`assistantId` 必须在 key 里，否则两个助手会共用同一份会话历史。**

`sessionId` 由调用方生成，没有任何机制保证它全局唯一。你文档里的示例就是
`?userId=u1&sessionId=s1` —— 拿这个 curl 打两个不同的助手，第二次就会接上第一次的历史。

这一段的取舍和**长期记忆**正好相反：用户事实是**用户级、跨助手共享**（刻意的），
会话上下文是**助手级、不该跨**。详见交付单 02 的 `SessionKey`。

**二、消息和摘要必须是同一套 key 规则 —— 这里原来有个真 bug。**

会话消息和会话摘要是两个 key 空间（`ai.mem.session:` / `ai.mem.summary:`），
但它们的**身份部分必须完全一致**，否则会出现"消息被清了、摘要还挂着"这种对不上的状态。

原来两处各写一遍拼接逻辑，摘要在那一处**漏了脱敏**：

```java
// ❌ 摘要存储原来的写法 —— 明文 userId 直接进了 Redis
private static final String KEY_PREFIX = "ai.mem.summary:";

@Override
public String get(Object sessionId) {              // ← 参数名叫 sessionId，实际传进来的却是 memoryId
    return redis.opsForValue().get(KEY_PREFIX + sessionId);
}
```

最终 key 是 `ai.mem.summary:u123::s456` —— **明文 userId 照样散落到 MONITOR / SLOWLOG / RDB 里**。
本篇第三节花了整页讲这个风险，结果摘要这条路上毫无防护。

> **根因不是疏忽，是命名。** 那个参数叫 `sessionId`，但它承载的是 `userId::sessionId`（memoryId）。
> 看名字的人会以为它是"我们自己生成的不透明 id"，于是放心地直接拼进 key —— 这正是消息存储
> 的注释里说的"可以保留明文"的那个东西。**名字骗了实现者。**
>
> 所以这一版的修法有两层：① key 构造收进 `SessionRedisKey` 一个类；
> ② 把那几个参数改名叫 `memoryId`，让名字和它承载的东西对上。

---

### 修法：key 构造只留一个地方

新增 `session/SessionRedisKey.java`，两个存储都用它 —— 见 4.1。

> **哈希用 SHA-256 而不是 MD5**：MD5 对短字符串（手机号）是可以被暴力反查的，等于没脱敏。SHA-256 加固定盐在生产上更稳妥。这里为了不引入额外配置，先不加盐——**上生产前请补上**。

---

# 第四节 · Redis 实现

## 4.0 先回答一个问题：为什么不用现成的？

LangChain4j 生态里**已经有一个** Redis 版的 `ChatMemoryStore`：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-redis</artifactId>
</dependency>
```

类在 `dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore`，用法：

```java
ChatMemoryStore store = RedisChatMemoryStore.builder()
        .host("localhost")
        .port(6379)
        .ttl(3600L)
        .prefix("chat:memory:")     // key 前缀，可配
        .build();
```

它**能用，也确实更省事**（少写 60 行）。所以这个选择必须给出理由，不然就是过度设计。

### 不用的四条理由，按强度排

**① key 脱敏它做不到（最硬的一条）**

它的 `prefix` 是**固定字符串**，key 的剩余部分仍然是 `memoryId` 原样。也就是说最终 key 长这样：

```
chat:memory:u123::s456
```

明文 userId 照样进 Redis。而我们前面第三节明确了要 **SHA-256 掉 userId** —— 这个能力它给不了，因为 key 的拼装在它内部。

**② Redis 连接配置要写两遍**

它的 builder 要 `host` / `port` / `password`，也就是**它自己管一套连接池**。而 Spring 应用里已经有 `spring-boot-starter-data-redis` 提供的 `StringRedisTemplate` 和连接工厂。用它意味着配置分两处：

```yaml
spring:
  data:
    redis:            # Spring 的
      host: localhost
langchain4j:
  redis:
    host: localhost   # 又是一份，改地址时容易漏
```

**③ 它用 Jedis，你用 Lettuce**

`spring-boot-starter-data-redis` 默认带的是 Lettuce。引 community-redis 会**再引入一套 Jedis 客户端**，两套并存。不是致命问题，但是白占内存和连接数。

**④ 版本线不同步**

注意它自己的版本号：`1.15.0-beta25` 这种形式 —— 说明 community 模块有**独立的 beta 发布线**，和 core 不是同一个节奏。你在 core 1.20.0 上，就需要 community 恰好有对应的 `1.20.0-betaXX`；没有的话你得降 core 版本，或者自己编译。

再加上 `beta` 这个标记本身意味着 API 可能变。

### 但有个更长远的原因

我们真正需要的**不是一个 `ChatMemoryStore` 的实现**，而是一个平台契约 `SessionMemoryStore`。将来要往里加东西：

- `listSessions(userId)` —— 管理端要能列出某用户的所有会话
- `deleteByUserId(userId)` —— **合规要求：用户有权要求删除自己的全部数据**

community 版给不了这些，到时候你还是得自己包一层。**现在写 60 行，比将来在别人代码上打补丁便宜。**

### 那什么时候该直接用现成的？

如果你**同时**满足这三条，就别自己写：

- 没有 key 脱敏要求（内部系统、userId 本来就是无意义的雪花 ID）
- 不介意 Jedis 和 Lettuce 并存
- community 版本恰好和你的 core 版本对得上

**这三条你一条都不满足。** 所以自己写。

> 顺带说一个反例，免得你以为"自己写一定更好"：如果你只是想在本地跑个 demo、不在意脱敏和依赖，`RedisChatMemoryStore` 三行就配好了，比我下面这 100 行强。**判断依据是上面那三条，不是"自己写的更可控"这种笼统的说法。**

## 4.1 `session/SessionRedisKey.java`

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

## 4.2 `session/RedisSessionMemoryStore.java`


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
 * 基于 Redis 的会话记忆存储。
 *
 * <p>用 StringRedisTemplate 而不是 RedisTemplate&lt;String, Object&gt;：
 * 我们存的就是 JSON 字符串，用专门的 String 模板省去序列化器的配置 ——
 * 而且能避免 JDK 序列化（把对象写成二进制，跨语言读不了、升级类结构还容易炸）。
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
        String json = redis.opsForValue().get(keyOf(memoryId));

        // 没有 或 空串：都当作"这个会话还没开始"，返回空列表而不是 null
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        // 必须再包一层 ArrayList。
        // ChatMessageDeserializer 返回的是 Gson 反序列化出来的列表，
        // 它的可变性没被承诺 —— 而 LangChain4j 拿到之后会直接 .add()。
        // 包一层可变副本，把不确定性挡在这里。
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            // 空列表不写 Redis。理由：Redis 里存一个 "[]" 和一个不存在的 key
            // 对读取方来说没区别（都返回空列表），但存着会白占内存、
            // 还会让"这个会话是否还活着"这个判断变复杂。
            redis.delete(keyOf(memoryId));
            return;
        }

        // 关键：每次写都用带 TTL 的 set，而不是先 set 再 expire。
        //
        // 这带来一个很重要的性质 —— TTL 是"滑动"的：
        // 只要这个会话还在被使用，它就一直不过期；
        // 一旦用户 48 小时没说话，整段历史自动消失。
        // 这正是我们想要的语义，不需要额外的清理任务。
        //
        // 反过来，如果你用 set 不带 TTL、另外写个定时任务扫过期 ——
        // 那就要处理"扫的时候正好有请求在写"的竞态，麻烦得多。
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
     * 详见本篇第三节。
     */
    private String keyOf(Object memoryId) {
        return SessionRedisKey.of(SessionRedisKey.MESSAGE_PREFIX, memoryId);
    }
}
```

## 4.3 `session/RedisSessionSummaryStore.java`

```java
package com.best.ai_memory.session.summary_store;

import com.best.ai_memory.session.SessionRedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 会话摘要的 Redis 存储。
 *
 * <p><b>注意参数名是 memoryId 而不是 sessionId。</b>
 * 方法签名上收到的是 {@link dev.langchain4j.memory.ChatMemory#id()}，
 * 也就是 {@code assistantId::userId::sessionId} 这个 memoryId，不是裸的 sessionId。
 * 上一版把它叫 sessionId，于是实现里放心地直接拼进 key，明文 userId 就进了 Redis ——
 * 名字和它承载的东西对不上，是那类 bug 的常见起点。
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

    /**
     * 摘要的 key 必须和消息的 key 走同一套规则 —— 只是前缀不同。
     * 否则会出现"消息按 A 规则清、摘要按 B 规则找"，永远对不上。
     */
    @Override
    public void save(Object memoryId, String summary) {
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

> **`SessionSummaryStore` 接口里的参数名也要跟着从 `sessionId` 改成 `memoryId`。**
> 它同样是从 `ChatMemory.id()` 拿到的 memoryId —— 叫错名字是会传染的。
> 这是交付单 02 第一节里那两个文件的改动，一起改掉。

---

# 第五节 · 测试（用真实 Redis，不用 Mock）

## 为什么不用 Mock

`RedisSessionMemoryStore` 的**全部价值**就在于"和真实 Redis 的交互对不对"：

- key 拼对了吗
- JSON 写进去、读出来还是同一批消息吗（类型有没有丢）
- TTL 真的设上了吗
- 空列表真的把 key 删了吗

这些用 `Mockito` mock 掉 `StringRedisTemplate` 之后，测的只是"我调了第几个方法、参数是什么"——**你写错的 key 前缀它照样通过**。

所以这个测试起一个真实的 Redis 容器。

## `test/.../session/RedisSessionMemoryStoreTest.java`

```java
package com.best.ai_memory.session;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 Testcontainers 起真实 Redis。
 * 需要本地有 Docker —— Testcontainers 会自动拉 redis:7-alpine 镜像。
 */
@Testcontainers
class RedisSessionMemoryStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private StringRedisTemplate redis;
    private RedisSessionMemoryStore store;

    private static final String MEMORY_ID = "u123::s456";

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        store = new RedisSessionMemoryStore(redis, Duration.ofHours(48));
    }

    @Test
    @DisplayName("没存过的会话返回空列表，而不是 null")
    void unknownSessionReturnsEmptyList() {
        assertThat(store.getMessages(MEMORY_ID)).isEmpty();
    }

    @Test
    @DisplayName("四种消息类型存取之后类型都不丢 —— 这是序列化最容易出问题的地方")
    void allMessageTypesSurviveRoundTrip() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是运筹眼助手"),
                UserMessage.from("帮我看下昨天的指标"),
                AiMessage.from("昨天同比上升 12%"),
                UserMessage.from("好的"));

        store.updateMessages(MEMORY_ID, messages);

        List<ChatMessage> loaded = store.getMessages(MEMORY_ID);

        // 断言具体类型，而不只是内容 —— 内容对了但类型退化成 Map，
        // 后面发给模型时会直接失败，而且报错位置离这里很远
        assertThat(loaded).hasSize(4);
        assertThat(loaded.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(loaded.get(1)).isInstanceOf(UserMessage.class);
        assertThat(loaded.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((UserMessage) loaded.get(1)).singleText()).isEqualTo("帮我看下昨天的指标");
        assertThat(((AiMessage) loaded.get(2)).text()).isEqualTo("昨天同比上升 12%");
    }

    @Test
    @DisplayName("key 里不能出现明文 userId")
    void userIdIsHashedInKey() {
        store.updateMessages("13800001111::s456", List.of(UserMessage.from("hi")));

        Set<String> keys = redis.keys("ai:mem:session:*");

        assertThat(keys).hasSize(1);
        // 这条断言是【安全约束】，不是功能测试。
        // 它变红说明有人把 keyOf 改回明文了 —— 那会把手机号写进
        // MONITOR / SLOWLOG / RDB，属于要立刻修的问题。
        assertThat(keys.iterator().next()).doesNotContain("13800001111");
    }

    @Test
    @DisplayName("写进去之后 TTL 确实设上了")
    void ttlIsApplied() {
        store.updateMessages(MEMORY_ID, List.of(UserMessage.from("hi")));

        String key = redis.keys("ai:mem:session:*").iterator().next();
        Long ttl = redis.getExpire(key);

        assertThat(ttl).isGreaterThan(0);                 // -1 表示没有过期时间
        assertThat(ttl).isLessThanOrEqualTo(48 * 3600L);
    }

    @Test
    @DisplayName("每次写都会刷新 TTL —— 活跃会话不过期")
    void ttlIsRefreshedOnWrite() throws Exception {
        store.updateMessages(MEMORY_ID, List.of(UserMessage.from("第一句")));
        String key = redis.keys("ai:mem:session:*").iterator().next();
        redis.expire(key, Duration.ofSeconds(60));        // 手动改成 60 秒

        store.updateMessages(MEMORY_ID, List.of(UserMessage.from("第二句")));

        assertThat(redis.getExpire(key)).isGreaterThan(60);
    }

    @Test
    @DisplayName("写空列表会把 key 删掉，而不是留个 [] 占位")
    void emptyListDeletesKey() {
        store.updateMessages(MEMORY_ID, List.of(UserMessage.from("hi")));
        assertThat(redis.keys("ai:mem:session:*")).hasSize(1);

        store.updateMessages(MEMORY_ID, List.of());

        assertThat(redis.keys("ai:mem:session:*")).isEmpty();
    }

    @Test
    @DisplayName("deleteMessages 清掉整个会话")
    void deleteRemovesKey() {
        store.updateMessages(MEMORY_ID, List.of(UserMessage.from("hi")));

        store.deleteMessages(MEMORY_ID);

        assertThat(store.getMessages(MEMORY_ID)).isEmpty();
        assertThat(redis.keys("ai:mem:session:*")).isEmpty();
    }
}
```

## ✅ 检查点 3

```bash
mvn -pl AI-Memory test
```

预期：

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

（01 的 6 + 02 的 11 + 03 的 7 = 24）

第一次跑会拉 `redis:7-alpine` 镜像，可能要等一两分钟。

**跑不起来怎么办**：

- 报 `Could not find a valid Docker environment` → 本地没装 Docker，先跳过这个测试类，装好再回来
- 报端口被占用 → Testcontainers 默认用随机端口映射到 6379，一般不会冲突；真冲突了重启 Docker
- 报 `LettuceConnectionFactory` 找不到 → `spring-boot-starter-data-redis` 没引上，回去看第一节

---

# 自查清单

- [ ] `mvn -pl AI-Memory test` 全绿（25 个）
- [ ] 你能说出为什么测试里要用真实 Redis 而不是 Mock `StringRedisTemplate`
- [ ] 你能说出 `userIdIsHashedInKey` 这条断言是在防什么
- [ ] 你能说出 TTL 为什么必须是"每次写都刷新"的滑动窗口

最后一条的答案：因为"会话活跃"这件事**没有别的信号**。Redis 看不到 HTTP 请求，它只知道有人写 key。如果 TTL 只在会话创建时设一次，那么一个聊了两个小时的长会话会在第 48 分钟突然丢掉全部历史——**而用户正在用**。

---

# 下一篇预告

**交付单 04 · 流式输出与助手装配。**

到这里你会第一次看到 `AiServices` 出场，`ChatMemoryProvider` 挂上去，然后一个 `TokenStream` 逐字吐给前端。04 做完，**你就有一个能用的多轮流式对话助手了**——虽然它还没有长期记忆。

04 会重点讲三个流式特有的坑：

1. `TokenStream` 的回调在**别的线程**上，所以你的 `@Transactional` 在那里无效
2. SSE 必须显式关掉响应缓冲，否则 token 会攒到最后一次性吐出来（你会以为是"不流式"）
3. 流式请求下 `chatMemory.add(aiMessage)` 发生在流**结束之后**——如果这里抛异常，用户已经看到完整回答了，但历史没存上
