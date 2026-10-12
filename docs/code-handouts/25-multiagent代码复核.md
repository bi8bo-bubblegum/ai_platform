# 25 · ai-multiagent 代码复核（弃用 API 扫描 + 三个 bug）

> **触发**：用户问"检查一下语法是否过时，graph.invoke 好像已经弃用了"
>
> **复核对象**：`ai-multiagent` 模块**真实落盘**的 11 个源文件（不是交付单）
>
> **方法**：`javap -v` 扫 `@Deprecated` + 读 1.9.2 的 **sources jar**（拿官方替代方案）+ **`mvn` 编译实测**

---

## 结论速览

| # | 级别 | 问题 | 位置 |
|---|---|---|---|
| **1** | 🔴 **起不来** | `approvalGate` 节点从未 `addNode`，但被三条边 + `interruptBefore` 引用 | `MultiAgentGraphConfig` |
| **2** | 🔴 **行为错** | 字符串转义写错，`routing` 不会被求值（编译能过） | `SupervisorNode:35` |
| **3** | 🟠 **弃用** | `invoke(Map, RunnableConfig)` 是 `@Deprecated(forRemoval)` | `AgentController:40` |
| **4** | ⚪ 缺口 | 缺 `/approve` 端点，`ApproveRequest` 定义了却没用 | `AgentController` |

**先说好消息**：`SupervisorAgent` 的枚举改造你已经落地了 ✓（`Routing` + 穷尽 `switch` + `@Description` 都在）。

---

## 1. 弃用 API：确实有，而且全模块只有一处

编译实测（`mvn -Dmaven.compiler.showDeprecation=true`）：

```
[WARNING] AgentController.java:[40,31] org.bsc.langgraph4j.CompiledGraph 中的
invoke(java.util.Map<java.lang.String,java.lang.Object>,org.bsc.langgraph4j.RunnableConfig)
已过时, 且标记为待删除
```

**"已过时, 且标记为待删除"** 就是 `@Deprecated(forRemoval = true)` —— 不是"不推荐"，是**计划移除**。

**改法（一行）：**

```java
// 改前
return run(() -> graph.invoke(input, config), threadId, config);

// 改后  —— 记得加 import org.bsc.langgraph4j.GraphInput;
return run(() -> graph.invoke(GraphInput.args(input), config), threadId, config);
```

**等价性有源码保证**，弃用版本的实现本身就是包一层：

```java
@Deprecated(forRemoval = true)
public Optional<State> invoke(Map<String,Object> inputs, RunnableConfig config) {
    return invokeFinal(inputs == null ? GraphInput.resume() : GraphInput.args(inputs), config)
            .map(NodeOutput::state);
}
```

> ⚠️ 顺便一个隐藏语义：那个三元表达式说明**传 `null` 会被当成「恢复」而不是「新输入」**。
> 你现在传的是非 null 的 map，走 `args` 分支，没问题。

### 全模块扫描结果：其余全部未弃用

逐个 `javap -v` 确认过，没有 `Deprecated` 标记的有：
`StateGraph` 构造器 / `addNode` / `addEdge` / `addConditionalEdges` / `compile`、
`CompileConfig.Builder` 全部方法、`RunnableConfig.Builder`、`GraphInput.*`、
`AsyncNodeAction.node_async`、`AsyncEdgeAction.edge_async`、`NodeAction` / `EdgeAction`、
`Channels.appender` / `Channels.base`、`GraphDefinition.START` / `.END`、
`GraphInterruptException.of`、`StateSnapshot.nextNodeId` / `.state`、
`AgentState.value(String)`（**单参数版** —— 双参数版才弃用）、
`RedisSaver` + `Builder`、`LC4jStateSerializer`、`MemorySaver`、`BaseCheckpointSaver`。

---

## 2. 🔴 `approvalGate` 没被声明 —— 应用**起不来**

`MultiAgentGraphConfig` 里声明了 **5 个**节点（`recallMemory` / `supervisor` / `expertA` / `expertB` / `writeMemory`），
但同时有 **4 处**在引用 `approvalGate`：

```java
.addEdge("expertA", GATE)      // ← 引用
.addEdge("expertB", GATE)      // ← 引用
.addEdge(GATE, "supervisor")   // ← 引用
.interruptBefore(GATE)         // ← 引用
```

**缺的就是这一行：**

```java
.addNode(GATE, node(new ApprovalGateNode()))
```

（`ApprovalGateNode` 类你已经写好了 ✓，只是没挂到图上）

### 它会炸，而且炸在启动时 —— 有源码为证

`StateGraph.compile(config)` 会 `new CompiledGraph<>(...)`，而构造器里有：

```java
// CHECK INTERRUPTIONS
for (String interruption : processedData.interruptsBefore() ) {
    if (!processedData.nodes().anyMatchById( interruption )) {
        throw StateGraph.Errors.interruptionNodeNotExist.exception(interruption);
    }
}
```

所以**服务启动阶段就会抛 `GraphStateException`**，报错大意是"中断点引用的节点不存在"。
这是好事 —— 早暴露，不是跑到一半才崩。

> 🔎 **一个反直觉的细节，值得单独记**：`Edge.validate()` **只校验边的起点，不校验终点**：
>
> ```java
> public void validate( StateGraph.Nodes<State> nodes ) throws GraphStateException {
>     if ( !Objects.equals(sourceId(), START) && !nodes.anyMatchById(sourceId()) ) {
>         throw StateGraph.Errors.missingNodeReferencedByEdge.exception(sourceId());
>     }
>     // ... 之后只检查 targets 有没有重复，不检查是否存在
> }
> ```
>
> 也就是说 `addEdge("expertA", "一个不存在的节点")` **不会**在 compile 时报错。
> 这次是 `interruptBefore` 那条路径替我们拦住了 ——
> **如果哪天去掉了 `interruptBefore`，这个错误会一直潜伏到运行时**。

---

## 3. 🔴 字符串转义写错 —— 编译能过，但 `routing` 不会被求值

`SupervisorNode` 第 35 行：

```java
MultiAgentState.MESSAGES, AiMessage.from("（调度：\" + routing + \"）")
//                                        ↑ 注意这里
```

`\"` 是**合法的转义双引号**，所以这整段是**【一个】字符串字面量** ——
`routing` 根本没参与拼接。实际追加进 `messages` 的文本永远是固定的：

```
（调度：" + routing + "）
```

**改法：**

```java
MultiAgentState.MESSAGES, AiMessage.from("（调度：" + routing + "）")
```

**为什么这个 bug 比看起来严重：**

`MESSAGES` 是 `Channels.appender(...)`（只增不减），而 `SupervisorNode.render()`
和 `ExpertAgent` 都会把 `messages` 渲染进提示词。所以这条无意义的固定文本会**每轮累积一条**，
并且**持续污染后续所有轮次的调度判断和专家回答**。

> 这类 bug 的可怕之处在于：**编译器不报错、日志里那句 `调度决定: {} -> {}` 还是正常的**
> （因为它是独立的一次 `log.info`），所以只看日志不会发现 —— 只有去读 Redis 里的 checkpoint
> 或者看到模型答得莫名其妙才会察觉。

---

## 4. ⚪ 缺 `/approve` 端点

`ApproveRequest` 这个 record 定义在文件末尾，但**没有对应的 `@PostMapping`**。
异步两段式只实现了第一段，所以：

- 检查点第 3 关（`waiting_approval` → `approve` → `done`）**跑不了**
- 检查点第 4 关（重启后 approve 仍能恢复）**跑不了**

**改法**（完整版在交付单 24 的「10. AgentController」）：

```java
@PostMapping("/approve")
public Map<String, Object> approve(@RequestBody ApproveRequest req) {
    String threadId = req.sessionId();
    RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

    log.info("收到审批: threadId={} approved={}", threadId, req.approved());

    return run(() -> graph.invoke(
                    GraphInput.resume(Map.of(MultiAgentState.APPROVED, req.approved())),
                    config),
            threadId, config);
}
```

---

## 5. 两个小项（可改可不改）

**① `@PostMapping("chat")` 缺前导斜杠。**
能正常工作（Spring 会拼成 `/agent/chat`），但规范写法是 `@PostMapping("/chat")`。

**② 编译输出里另有一条与本题无关的警告**，各模块都出现 5 次：

```
[WARNING] 未知的枚举常量 com.baomidou.mybatisplus.annotation.IdType.AUTO
```

这和 `ai-memory` 的 `@TableId(type = IdType.AUTO)` 有关，通常意味着
**mybatis-plus 相关 jar 存在版本不一致**。**不影响运行**（编译仍 SUCCESS），
但值得哪天 `mvn dependency:tree` 看一眼 —— 它和 multiagent 模块无关。

---

## 6. 梳理完整时序时，另外看出两条

（这两条不在上面的 bug 清单里，是逐节点走一遍流程才显出来的）

### ① ⚠️ `expertB` 也会触发审批 —— 而它的动作是 `noop`

`ExpertNode` **无条件**写 `pendingAction`：

```java
return Map.of(
        MultiAgentState.MESSAGES, AiMessage.from("[" + role + "]" + reply),
        MultiAgentState.PENDING_ACTION, plannedAction      // ← 无条件写
);
```

而 `expertB` 的构造参数是 `new ExpertNode("查询分析", "noop", expertAgent)`：

```java
.addNode("expertA", node(new ExpertNode("数据库操作分析", "deleteTestData(scope=last-30-days)", expertAgent)))
.addNode("expertB", node(new ExpertNode("查询分析", "noop", expertAgent)))
```

→ **任何走 expertB 的请求（纯查询）也会停在审批闸门**，让用户批准一个 `noop`。

根因不是写错，是**能力缺失**：`CompileConfig.interruptBefore(...)` 是**编译期配置**，
表达不了"只有危险操作才停下来"。这正是交付单 24「三个已知代价」里的第 3 条。
要修得改用 `InterruptableAction` 接口（按 state 决定本次要不要中断）。

**当前的实际表现**：查个数据也要点一次"批准"。

### ② `writeMemory` 的 sessionId 硬编码 `"graph"` —— 根因是 state 里没有 sessionId

```java
String memoryId = assistantId + "::" + userId + "::graph";
//                                              ^^^^^^^ 硬编码
```

三段格式正确（`SessionKey.parse` 能解析），长期记忆按 `userId` 检索**不受影响** ✓。
代价是 `source_session` 永远是 `"graph"` → **丢掉"这条事实是哪次会话抽出来的"这个溯源信息**。

**根因**：`MultiAgentState` 里**没有 sessionId 这个 channel** ——
`WriteMemoryNode` 拿不到"当前是哪个会话"，而 `NodeAction` 的签名里也**没有 `RunnableConfig`**
（所以它连 `threadId()` 都读不到）。硬编码 "graph" 是无奈之举。

**修法**：给 state 开一个 channel，由 Controller 从 `req.sessionId()` 写入：

```java
// MultiAgentState
public static final String SESSION_ID = "sessionId";
// SCHEMA 里加：SESSION_ID, Channels.base(() -> "")
public String sessionId() { return this.<String>value(SESSION_ID).orElse(""); }

// AgentController.chat(...) 的初始 input 里
input.put(MultiAgentState.SESSION_ID, req.sessionId());

// WriteMemoryNode
String memoryId = assistantId + "::" + userId + "::" + state.sessionId();
```

> 这条顺带说明一个**通用规律**：**凡是节点需要用到的请求上下文，都必须在 state 里显式开一个 channel** ——
> 因为 `NodeAction` 只能看到 state，看不到 HTTP 请求、也看不到 `RunnableConfig`。
> `userId` 当初就是这么进来的；`sessionId` 只是漏了一个。

---

## 7. 为什么 `threadId` 不像 `memoryId` 那样用 `assistantId::userId::sessionId`？

> 这是用户的一个好问题。查了 `RedisSaver` 源码之后，答案是：**不该照抄，但现在有一个隐患。**

### 先看现在的差异

| | `memoryId`（ai-memory） | `threadId`（langgraph4j） |
|---|---|---|
| 长相 | `assistantId::userId::sessionId` | `sessionId` |
| 谁解析 | `SessionKey.parse`（有格式约定 + 校验） | 只当字符串拼进 Redis key |
| 作用域 | **助手 × 用户 × 会话** | 会话 |
| 为什么有 userId | `ChatMemory` 那层**只有 memoryId 一个参数**，不编进去就拿不到用户 | **不需要** —— `state` 里有 `USER_ID` channel |

**第一条关键差异：langgraph4j 不该带 userId。**

`userId` 已经在 state 里了。硬编进 threadId 反而会**把我们刚在 ai-memory 修掉的问题重新引进来** ——
明文 userId 进 Redis key（MONITOR / SLOWLOG / RDB 都会散落）。
`memoryId` 之所以必须做 SHA 脱敏，正是因为躲不开这个；threadId 完全躲得开。

**结论：`assistantId::userId::sessionId` 这个格式不该照抄。**

### 第二条差异：langgraph4j 的 `threadId` 其实是「threadName」

这是查源码才发现的反直觉点 —— `RunnableConfig.threadId(x)` 里的 `x` **不是真正的存储 key**：

```java
final var threadName = threadId(config);                       // 我们传的 "s1"
final var threadNameKey = keyNamingStrategy.threadNameKey(threadName);
final var threadId = redissonClient.<String>getBucket(threadNameKey, StringCodec.INSTANCE).get();
//                                                          ↑ 真正的内部 threadId，一个 UUID
```

默认 key 命名（`DefaultKeyNamingStrategy`）：

```
langgraph4j:thread:{threadId}
langgraph4j:thread:name:{threadName}:active      ← 我们的 "s1" 出现在这里
langgraph4j:checkpoint:{checkpointId}
langgraph4j:thread:{threadId}:checkpoints
```

**所以串号的机制是**：两个不同的图如果用**同样的 threadName**（比如都叫 `"s1"`），
会命中同一个 `langgraph4j:thread:name:s1:active` → 取到同一个内部 threadId → **共用 checkpoint 空间**。
而两个图的 state 结构不同，共用之后反序列化会失败或字段错乱。

### 好消息：隔离手段是现成的，而且比塞进 id 更干净

`RedisSaver.Builder` 有 **`keyNamingStrategy(KeyNamingStrategy)`**（源码确认存在，
不配则用 `DefaultKeyNamingStrategy`）。`KeyNamingStrategy` 有 5 个方法可覆写：
`threadKey` / `threadNameKey` / `checkpointKey` / `checkpointsKey` / `keyPrefix`。

```java
.keyNamingStrategy(new KeyNamingStrategy() {
    private static final String P = "lg4j:multiagent:";
    @Override public String threadKey(String id)          { return P + "thread:" + id; }
    @Override public String threadNameKey(String name)    { return P + "thread:name:" + name + ":active"; }
    @Override public String checkpointKey(String cid)     { return P + "checkpoint:" + cid; }
    @Override public String checkpointsKey(String id)     { return threadKey(id) + ":checkpoints"; }
    @Override public String keyPrefix()                   { return P; }
})
```

### 三种做法，我的判断

| 做法 | 评价 |
|---|---|
| **A.** `threadId = assistantId::userId::sessionId` | ❌ 照抄格式 —— 把**明文 userId 塞进 Redis key**，正是刚修掉的那类问题 |
| **B.** `threadId = assistantId::sessionId` | ○ 达到隔离目的、不引入用户信息；但把"部署隔离"混进了业务 id |
| **C.** 配 `KeyNamingStrategy` 加服务前缀，`threadId` 保持 `sessionId` | ✅ **推荐** —— 隔离是**部署问题**，用前缀表达比塞进 id 更自然，业务 id 保持语义单纯 |

**什么时候必须处理**：出现**第二个 langgraph4j 服务**、且与这个共用同一个 Redis 时。
**现在只有一个图应用 + Redis 独占 → 现在的写法没问题，不用改。**

> 顺带：这也解释了为什么两套 id 的"格式要求"本来就不同 ——
> `memoryId` 是**有语义的复合 key**（要 parse、要校验、要能反推用户）；
> `threadId` 是**不透明标识**（只被拼进 key，从不解析）。不该无脑统一。

---

## 改完后的验证

```bash
# ① 弃用警告应该消失（第 3 条）
mvn -o -DskipTests clean compile -Dmaven.compiler.showDeprecation=true

# ② 应该能起来了（之前会在 compile 图时抛异常）
mvn -o -pl ai-multiagent spring-boot:run
```

**启动成功的标志**：日志出现 `Checkpoint 已启用: RedisSaver ... `。

> 第 1 条（`approvalGate`）和第 2 条（转义）**必须改**，否则应用根本起不来；
> 第 3 条是"计划移除"，也建议一起改掉；第 4 条取决于你是否现在就想测两段式。

---

## 附：这次复核用到的方法（可复用）

1. **`javap -v ... | grep -B20 "Deprecated: true"`** —— 快速扫一个类里哪些成员被弃用
2. **读 sources jar 里的 `@Deprecated` javadoc** —— `javap` 只告诉你"弃用了"，
   **javadoc 才给官方替代方案**（这次的 `GraphInput` 就是这么找到的）
3. **`mvn compile -Dmaven.compiler.showDeprecation=true`** —— 让**编译器**告诉你
   自己代码里哪一行用了弃用 API（比人工 grep 可靠，还能给出精确行列）
4. 想确认"某个错误什么时候抛"时，**直接读框架源码**（`compile()` → `validateGraph()` → `Edge.validate()`）
   —— 比猜准得多，这次就靠它把"启动时抛"和"运行时抛"分清了
