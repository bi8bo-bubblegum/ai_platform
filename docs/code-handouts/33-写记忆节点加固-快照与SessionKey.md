# 33 · 写记忆节点的一行加固

> **前置**：32 号（如果需要先敲 32 号的话）。
>
> **规模**：两个小改动，加起来两行。**不需要大动，但都不做会让将来踩一次坑。**

---

## 1. 改动一：传给异步任务的消息列表改成不可变快照

### 为什么

`MESSAGES` 这个 channel 是用 `Channels.appender(ArrayList::new)` 声明的。
我去查了它的合并语义（`AppenderChannel$ReducerDisallowDuplicate` 的字节码）：

```
apply(oldList, newList):
    if (oldList == null) return newList;       // 不拷贝
    for (item : newList) oldList.add(item);    // ★ oldList 被【原地修改】
    return oldList;
```

**结论：`MESSAGES` 的值从头到尾是同一个 `ArrayList` 实例** ——
图的每个节点读到的都是它、往它上面加。所以节点里 `state.messages()`
拿到的是**"活引用"，不是快照**。

**现在为什么没出事**：`writeMemory` 是全图最后一个节点（`writeMemory → END`），
它执行完图就结束了，之后没有任何人再动这个 list。
下一次请求会从 Redis 反序列化出**新的** list，所以跨请求也不共享。

**但这是脆弱的**。只要发生下面任何一件事，就立刻变成真 bug：

- 在 `writeMemory` **后面**加节点（比如"写完记忆再发个通知"）
- 把 `writeMemory` **挪到图中间**（比如"每轮到一定长度就落一次盘"）
- 将来给 `MESSAGES` 换成别的 reducer

那时的症状是：异步抽取线程正在遍历列表，主线程同时往里 `add`
→ `ConcurrentModificationException`，
而**这个异常发生在异步线程里**，只会被 `recordAsync` 内部那层 `catch`
吞成一条 error 日志 —— 你看到的现象是"记忆偶尔不生效"，而不是任何报错。

### 改法

路径：`ai-multiagent/src/main/java/com/best/multiagent/node/WriteMemoryNode.java`

```java
        try {
-            recorder.recordAsync(memoryId, state.messages());
+            // ★ 传一份不可变快照，而不是 state 里那个活引用。
+            //   MESSAGES 的 reducer 是【就地追加】（见 33 号文档第 1 节），
+            //   也就是 state.messages() 返回的 list 会被后续节点继续修改。
+            //   把它交给异步线程 = 埋一个只在异步侧报错的并发修改异常。
+            //   List.copyOf 是一次浅拷贝（几百个元素、微秒级），
+            //   顺带把"交给异步任务的东西是不可变的"这个意图写进了代码。
+            recorder.recordAsync(memoryId, List.copyOf(state.messages()));
        } catch (Exception e) {
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
```

> `List.copyOf` 的语义：返回**不可变**列表。
> 它拒绝 `null` 元素（这里不会有），而且如果传入的已经是不可变列表它会直接复用 ——
> 所以对"已经不可变"的调用方是零成本的。

---

## 2. 改动二：memoryId 用 `SessionKey` 构造，不再手拼

### 为什么

同一个文件里现在是这样：

```java
String memoryId = assistantId + "::" + userId + "::" + state.sessionId();
```

**这个写法已经出过一次 bug 了** —— 之前少了第二个 `::`，导致
`ai-multiagent` 的长期记忆一直静默写不进去（因为 `SessionKey.parse` 在
`recordAsync` 的**同步段**抛出，被节点这层 catch 接成了 warn）。

而项目里**本来就有** `SessionKey`，它的职责就是"三段式 memoryId 的唯一构造点"，
而且它的紧凑构造器还会校验"前两段不含 `::`、sessionId 非空"——
手拼等于把这些校验全绕过去了。

### 改法

```java
// 新增 import
import com.best.ai_memory.session.SessionKey;

        // ★ 用 SessionKey 构造，不手拼。
        //   分隔符、校验规则都只有一处定义；手拼字符串是已经出过一次事故的写法。
        String memoryId = SessionKey.of(assistantId, userId, state.sessionId()).toMemoryId();
        try {
            recorder.recordAsync(memoryId, List.copyOf(state.messages()));
        } catch (Exception e) {
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
```

> **注意：不要顺手去改 `recordAsync` 的签名。**
> 它刻意只收 `memoryId`（`assistantId`/`userId`/`sessionId` 由它自己 `parse`）——
> 因为另一个调用方（会话记忆那条链路上的 `ChatMemoryProvider`）手里**只有 memoryId**。
> 改成收三个参数会让那边没法调用，或者被迫再拼一次。
> **"少传两个参数就少一处传反的可能"** 这个设计是有原因的，别动。

---

## 3. 验收

### 3.1 编译

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

### 3.2 回归：记忆还能正常写进去

这个改动**正常流程下看不出任何区别** —— 它是防御性的。
所以验收就是"确认没坏"：

```bash
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"我叫小明，喜欢喝美式","sessionId":"s-mem"}'
# 等几秒（异步抽取），然后查库：
#   SELECT * FROM ai_user_memory ORDER BY id DESC LIMIT 5;
# 有新增行 → 通过
```

日志里应该**没有** `长期记忆写入触发失败`，也没有 `记录用户记忆失败`。

---

## 4. 顺带记下、但**现在不做**的一件事

查这个问题的过程中发现：抽取任务用的 `taskExecutor` 是 Spring Boot 自动配置的
`applicationTaskExecutor`，默认参数是：

| 参数 | 默认值 | 实际含义 |
|---|---|---|
| `corePoolSize` | 8 | |
| `queueCapacity` | `Integer.MAX_VALUE` | **队列永不填满** |
| `maxPoolSize` | `Integer.MAX_VALUE` | **永不扩容** |

**后两个组合起来 = 真实并发上限就是 8**（队列无限大，永远触发不了扩容）。

而抽取一次要跑一次 LLM 调用（几秒），所以：

- 8 个并发抽取就占满，第 9 个开始排队
- **不阻塞图**（提交是异步的），只是"记忆生效"越来越晚
- 这个池是**全应用共用**的，别的 `@Async` 也会被挤

**为什么现在不做**：目前没有真实的并发压力，
给它单独建池属于"为想象出来的问题买单"——而且池大小该配多少，
**要看真实的抽取耗时和 QPS 才能定**，现在拍的数字多半是错的。

**触发条件**（出现任一再说）：
① 日志里出现"记忆生效要等十几秒"；② 有人反馈"刚说过的事助手不记得"；
③ 抽取任务开始挤压其他 `@Async`。

**那时的做法**：给 `UserMemoryRecorder` 配一个专用
`ThreadPoolTaskExecutor`（固定 2~4 线程 + **有界队列** + `CallerRunsPolicy`），
用 `@Qualifier` 注入。**关键是有界队列** ——
无界队列会让积压变成"静默延迟"，有界队列才会让积压**可见**（拒绝或降级）。
