# 26 · threadId 与 checkpoint key 的隔离准备

> **触发**：用户提出"虽然现在没有第二个图，但是应该为此提前考虑"
>
> **前置**：`25-multiagent代码复核.md` 第 7 节 —— threadId 与 memoryId 为什么该用不同格式

---

## 0. 先辨析：这不违反"不提前抽象"

项目有一条既定纪律：**只有一个使用者时不抽抽象**。它是被反复执行过的 ——
`MemoryAwareAssistantFactory` 被否掉、`EmbeddingModelRegister` 被劝退、`ai-memory` 不合并进 `ai-common`。

但那条纪律的判据是**「形状未知」**：

> 抽抽象时，形状必须从**真实用例**里长出来。第二个用例还没出现 → 形状靠猜 → 必返工。

而"提前考虑 threadId / key 的命名空间"这件事，判据完全不同，是**「可逆性」**：

> **一旦有了存量数据、或对外承诺过格式，改它的成本就随时间上升。**

| | 判据 | 该什么时候定 |
|---|---|---|
| 抽 `BaseAgentState`（第二个图的 state 可能长什么样？） | **形状未知** | ❌ 等第二个图出现——现在抽必返工 |
| 决定 threadId / checkpoint key 的命名空间 | **不可逆**（存量数据 + 前端约定） | ✅ **现在**（还没数据，零成本） |

**一句话：抽象要等形状，契约要趁没数据。**

现在正好处在"零成本"的窗口里 —— 服务还没跑起来（缺 `approvalGate`，起不来），
Redis 里一条 checkpoint 都还没有。**过了这个窗口，改格式就要迁移。**

---

## 1. 两个动作

### 动作 A：给 checkpoint key 加服务命名空间（**立刻生效**）

**为什么现在是隐患。** langgraph4j 的默认 key 里**没有"哪个服务/哪个图"这个维度**：

```
langgraph4j:thread:{threadId}
langgraph4j:thread:name:{threadName}:active
langgraph4j:checkpoint:{checkpointId}
langgraph4j:thread:{threadId}:checkpoints
```

而 `RunnableConfig.threadId(x)` 里的 `x` 其实被当作 **threadName** 用 ——
框架拿它查 bucket 拿到内部 UUID，才是真正的存储标识。
所以**两个服务只要用了相同的 threadName（都叫 `"s1"`），就会共用 checkpoint 空间**。

Redis 独占时看不出问题；**一旦共用，症状是"历史对话串了"或反序列化失败** ——
而且这种错**不会在启动时报**，只会表现成诡异的行为。

**新增 `graph/NamespacedKeyNamingStrategy.java`** —— 见第 2 节。

**改 `MultiAgentGraphConfig`：加一行**（第 2 节有完整文件）

```java
.keyNamingStrategy(new NamespacedKeyNamingStrategy(assistantId))
```

`assistantId` 这个方法参数已经有了（`${ai.multiagent.assistant-id:${spring.application.name:multiagent}}`），
它本来就是"这个服务是谁"的语义，**正好复用，不用新增配置**。

**效果：**

| key | 改前 | 改后 |
|---|---|---|
| thread | `langgraph4j:thread:{uuid}` | `langgraph4j:ai-multiagent:thread:{uuid}` |
| threadName | `langgraph4j:thread:name:s1:active` | `langgraph4j:ai-multiagent:thread:name:s1:active` |
| checkpoint | `langgraph4j:checkpoint:{cid}` | `langgraph4j:ai-multiagent:checkpoint:{cid}` |

格式**照抄** `DefaultKeyNamingStrategy`，只把 `langgraph4j:` 换成 `langgraph4j:<namespace>:` ——
形状不变、只多一层前缀，是风险最低的改法。

> ⚠️ **为什么不在 `threadId` 里加前缀，而是用 `KeyNamingStrategy`？**
> 因为隔离是**部署问题**，不是业务标识问题。用 key 前缀表达更自然，也让 `threadId`
> 保持"就是一次会话"这个单纯的语义。
> 而且 `KeyNamingStrategy` 是 langgraph4j **专门为此留的扩展点** —— 它的 javadoc 原话是
> *"Implementations can provide custom key naming conventions to **integrate with existing
> Redis key structures** or to follow organizational naming standards."*

### 动作 B：把 `threadId` 的构造收口到一个方法（**防漏改**）

现在 `RunnableConfig.builder().threadId(...)` 在 Controller 里**出现两次**（`/chat` 和 `/approve`），
各拼一遍。两个问题：

1. **将来要改格式时，必漏一处。** 这不是假设 —— `ai-memory` 的 `SessionRedisKey` 就是被这个教训
   逼出来的：当初 `ai.mem.session:` 和 `ai.mem.summary:` 两处各写一遍拼接逻辑，
   摘要那处漏了脱敏，**明文 userId 直接进了 Redis**。
2. **现在没有任何校验。** `sessionId` 由前端传来，null / 空串 / 含 `::` 时行为未定义。

收进 `GraphThreadIds.of(sessionId)` 之后：**要改只改那一个方法，调用点一行不用动。**

**（注意：`of()` 现在不做任何格式加工，只做校验 —— 加工一旦开始就很难回头，
刻意留到真需要时再动。）**

---

## 2. 完整代码

### 2.1 新建 `ai-multiagent/.../graph/NamespacedKeyNamingStrategy.java`

```java
package com.best.multiagent.graph;

import org.bsc.langgraph4j.checkpoint.KeyNamingStrategy;

/**
 * 给 checkpoint 的 Redis key 加一层服务命名空间。
 *
 * <p><b>为什么需要它。</b> langgraph4j 的默认 key（{@code DefaultKeyNamingStrategy}）长这样：
 * <pre>
 * langgraph4j:thread:{threadId}
 * langgraph4j:thread:name:{threadName}:active
 * langgraph4j:checkpoint:{checkpointId}
 * langgraph4j:thread:{threadId}:checkpoints
 * </pre>
 * 里面<b>没有"是哪个服务 / 哪个图"这个维度</b>。
 *
 * <p>而 {@code RunnableConfig.threadId(x)} 里的 {@code x} 其实是被当成 <b>threadName</b> 用的 ——
 * 框架拿它去查一个 bucket，取出内部 UUID 才是真正的存储标识。所以：
 * 两个服务只要用了相同的 threadName（比如都叫 {@code "s1"}），就会命中同一个
 * {@code langgraph4j:thread:name:s1:active} → 取到同一个内部 threadId → <b>共用 checkpoint 空间</b>。
 * 两个图的 state 结构不同的话，共用之后反序列化会失败或字段错乱。
 *
 * <p><b>为什么在这里做，而不是把命名空间拼进 threadId。</b>
 * 隔离是<b>部署问题</b>，不是业务标识问题 —— 用 key 前缀表达更自然，
 * 也让 threadId 保持"就是一次会话"这个单纯的语义。
 * （{@code KeyNamingStrategy} 是 langgraph4j 专门为此留的扩展点。）
 *
 * <p>格式<b>照抄</b> {@code DefaultKeyNamingStrategy}，只把 {@code langgraph4j:}
 * 换成 {@code langgraph4j:<namespace>:} —— 形状不变、只多一层前缀，风险最低。
 */
public class NamespacedKeyNamingStrategy implements KeyNamingStrategy {

    private final String prefix;

    /**
     * @param namespace 服务 / 图的命名空间。建议直接用 {@code spring.application.name}，
     *                  或项目里的 {@code assistantId}（两者在本项目里同义）
     */
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

### 2.2 新建 `ai-multiagent/.../graph/GraphThreadIds.java`

```java
package com.best.multiagent.graph;

/**
 * threadId 的<b>唯一</b>构造点。
 *
 * <p><b>为什么要收口。</b> 现在 {@code RunnableConfig.builder().threadId(...)} 在 Controller 里
 * 出现了两次（{@code /chat} 和 {@code /approve}），各拼一遍。两个问题：
 * <ol>
 *   <li><b>将来要改格式时必漏一处</b> —— ai-memory 的 {@code SessionRedisKey} 就是被这个教训
 *       逼出来的：两处各写一遍拼接逻辑，摘要那处漏了脱敏，明文 userId 进了 Redis</li>
 *   <li><b>现在没有校验</b> —— sessionId 由前端传来，null / 空串 / 含分隔符时行为未定义</li>
 * </ol>
 *
 * <p>收进这里之后，<b>要改只改这一个方法</b>，所有调用点一行不用动。
 *
 * <p><b>关于命名空间。</b> 现在<b>不</b>往 threadId 里加服务名前缀 ——
 * 那件事交给 {@link NamespacedKeyNamingStrategy} 在 Redis key 层做（更干净）。
 * 将来如果出现<b>"一个服务里跑多个图"</b>（那时所有图共用一个 saver，key 前缀不够区分），
 * 才需要把图表识加到这里 —— 到那时只改 {@link #of} 的实现即可：
 * <pre>
 * return graphId + "::" + sessionId;
 * </pre>
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

### 2.3 改 `MultiAgentGraphConfig` —— `RedisSaver` 的 builder 链

```java
        var saverBuilder = RedisSaver.builder()
                .host(redisHost)
                .port(redisPort)
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))

                // 【新增】给 key 加服务命名空间。assistantId 已经是"这个服务是谁"的语义，
                // 不用新增配置项。不加的话，将来和别的图应用共用 Redis 时会串号。
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(assistantId))

                .ttl(ttlHours, TimeUnit.HOURS);
```

新增 import：

```java
import com.best.multiagent.graph.NamespacedKeyNamingStrategy;
```

### 2.4 改 `AgentController` —— 两处

```java
// /chat 里
String threadId = GraphThreadIds.of(req.sessionId());        // 原来：req.sessionId

// /approve 里
String threadId = GraphThreadIds.of(req.sessionId());        // 原来：req.sessionId
```

新增 import：

```java
import com.best.multiagent.graph.GraphThreadIds;
```

> 顺带：`/chat` 那处的 `graph.invoke(input, config)` 也要改成
> `graph.invoke(GraphInput.args(input), config)`（见 25 号第 1 节）。

---

## 3. 验证

```bash
# ① 编译
mvn -o -DskipTests clean compile -Dmaven.compiler.showDeprecation=true

# ② 起服务，跑一次对话让它停在闸门
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"请删除上个月测试数据","sessionId":"s1"}'

# ③ 看 key 有没有带上命名空间
redis-cli --scan --pattern 'langgraph4j:*'
```

**期待看到：**

```
langgraph4j:ai-multiagent:thread:name:s1:active
langgraph4j:ai-multiagent:thread:<内部UUID>
langgraph4j:ai-multiagent:thread:<内部UUID>:checkpoints
langgraph4j:ai-multiagent:checkpoint:<checkpointId>
```

如果前缀还是光秃秃的 `langgraph4j:`，说明 `keyNamingStrategy` 没生效。

---

## 4. 明确**不**做的（同样是"提前考虑"的一部分）

| 不做 | 为什么 |
|---|---|
| 抽 `BaseAgentState` 抽象基类 | **形状未知** —— 第二个图的 state 有哪些 channel 现在只能猜。这是典型的"该等第二个用例" |
| 把命名空间**也**塞进 threadId | 和 key 前缀是**替代关系**，两个都做等于双重保险、双重维护。选 key 前缀即可 |
| 现在就设计"单服务多图"的路由 | 更远的将来，而且真到那时多半是拆成多个服务（一图一服务），不会真在一个进程里跑多图 |
| 加 `withGraph(graphId, sessionId)` 备用方法 | **不被调用的代码就是死代码**。真需要时改 `GraphThreadIds.of` 的实现即可，成本一样 |

---

## 一句话总结

> **动作 A（key 前缀）是"现在花 20 行、将来省一次数据迁移"。**
> **动作 B（threadId 收口）是"现在花 15 行、将来省一次漏改排查"。**
>
> 两者都**不引入抽象**，只是把两个"决定点"提前定下来并收到一处 ——
> 这正是"提前考虑"和"提前抽象"的区别所在。
