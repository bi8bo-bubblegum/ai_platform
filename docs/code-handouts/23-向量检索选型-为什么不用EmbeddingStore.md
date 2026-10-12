# 23 · 向量检索选型：为什么不用 `EmbeddingStore`

> 按项目纪律：**自己写某个组件之前，必须回答"为什么不用现成的"**。
> 这一篇正式回答记忆模块的向量部分。此前只在交付单 06 里顺带提过一句，不够。

---

## 0. 先把问题精确化：我们用了什么、没用什么

"不用 LangChain4j 的向量 API"这个说法**不准确**。实际上：

| LangChain4j 提供的 | 我们用了吗 |
|---|---|
| `EmbeddingModel`（向量化） | ✅ **用了** —— `EmbeddingModelRegister.getDefault()` |
| `ContentRetriever`（检索注入点） | ✅ **用了** —— `UserMemoryContentRetriever implements ContentRetriever` |
| `EmbeddingStore`（向量存储抽象） | ❌ 没用 |
| `EmbeddingStoreContentRetriever`（现成的检索器） | ❌ 没用 |

**所以真正的问题是**：要不要把"向量存储 + 相似度检索"这一小段换成
`EmbeddingStore` + `EmbeddingStoreContentRetriever`。

> 这个区分很重要 —— 我们**并不反对**用框架抽象，`ContentRetriever` 这个插桩点就用得很对。
> 不用的只是**其中一个**抽象，而原因是它的前提在我们这里不成立。

---

## 1. 现成的有什么（实测事实）

### 1.1 `EmbeddingStore<Embedded>` 接口（core 1.20.0）

```java
public interface EmbeddingStore<Embedded> {
    String add(Embedding);
    String add(Embedding, Embedded);
    List<String> addAll(List<Embedding>, List<Embedded>);
    void removeAll(Collection<String>);
    void removeAll(Filter);
    void removeAll();
    EmbeddingSearchResult<Embedded> search(EmbeddingSearchRequest);
    CompletableFuture<EmbeddingSearchResult<Embedded>> searchAsync(EmbeddingSearchRequest);
}
```

`EmbeddingSearchRequest` 支持三个参数：`maxResults` / `minScore` / `filter`
（`filter` 是 metadata 过滤，`filter/comparison/` 下有 `IsEqualTo` / `IsIn` / `IsGreaterThan` 等）。

### 1.2 `InMemoryEmbeddingStore`（在**主包** `langchain4j` 里，不是 core）

**它没有索引结构，检索是线性扫描 —— 这是本文最关键的实测事实。** 验证方式：

```bash
# ① 类里没有任何 ANN / 索引相关的字段（只有 Entry 集合）
javap -p -classpath langchain4j-1.20.0.jar \
      dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore

# ② search 的字节码里直接出现余弦相似度调用 → 逐条算，不是索引查
javap -p -c -classpath langchain4j-1.20.0.jar \
      ...InMemoryEmbeddingStore | sed -n '/search(/,/^$/p' | grep -c CosineSimilarity
# → 2（命中）
```

它有 `serializeToJson()` / `fromJson()` / `serializeToFile()`，所以**理论上可以落盘持久化**。

### 1.3 `EmbeddingStoreContentRetriever`（在 core）

```java
EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)          // EmbeddingStore<TextSegment>
        .embeddingModel(embeddingModel)
        .maxResults(5)                  // 或 dynamicMaxResults(Query -> Integer)
        .minScore(0.6)                  // 或 dynamicMinScore(Query -> Double)
        .dynamicFilter(query -> ...)    // 按 metadata 过滤
        .build();
```

**它比预期完善** —— `minScore`、`maxResults`、按 Query 动态过滤都有。

### 1.4 外部向量库实现

| 实现 | 需要什么 |
|---|---|
| `PgVectorEmbeddingStore` | **PostgreSQL + pgvector 扩展**（你的库是 MySQL） |
| 社区版 Chroma / Milvus / Qdrant | 新增一个中间件要运维 |
| Redis | 需要带 RediSearch 模块的 Redis |

> ⚠️ **一个容易误会的点**：本机 `~/.m2` 里有 `langchain4j-pgvector-1.20.0-beta30`（2026-09-09 下载），
> 但**本项目从未引用它**（源码和 pom 里 grep 不到，是别的项目或探索留下的）。
> 如果以为项目在用 pgvector，那是误会。

---

## 2. 不用的四条理由

### 理由一（最硬）：`EmbeddingStore` 不带业务过滤，候选集是全库

这是本文的核心。看两条路径的差别：

```
方案 A · EmbeddingStore
  query → [库内] → 遍历全部 N 条算相似度 → top-K
                   ↑ N = 全平台事实总和，随用户数增长

方案 B · 当前实现
  query → SQL  WHERE user_id = ?  → 应用内算余弦（≤ 500 条）→ top-K
          ↑ 过滤发生在【算之前】，候选集恒定为单用户量级
```

**`EmbeddingStore.search` 的 `filter` 参数做不到这件事吗？** 语法上做得到
（把 userId 放进 metadata，用 `IsEqualTo` 过滤）。

**但 `InMemoryEmbeddingStore` 的实现里，过滤和相似度计算都在同一次线性扫描内** ——
它没有索引能先缩小集合。所以：

- 方案 A 的复杂度：O(全平台事实数)
- 方案 B 的复杂度：单用户事实数，**恒定**

数据库的 `WHERE` 子句替我们承担了"向量库索引"的职责。**这不是实现细节，是复杂度的量级差异。**

### 理由二：`EmbeddingStore` 的五个操作里，四个对不上

`UserMemoryStore` 现在有五个方法，逐条对照：

| 我们需要的 | `EmbeddingStore` 给得了吗 |
|---|---|
| `insert(memory)` | ✅ `add(Embedding, Embedded)` |
| `findByUserId(userId, limit)` | ❌ 只有 `search`（必须先有 query 向量） |
| `countByUserId(userId)` | ❌ 没有计数能力 |
| `deleteOldest(userId, n)` | ❌ 没有"按时间删最老 n 条" |
| `deleteAll(userId)` | ⚠️ `removeAll(Filter)`，前提是 userId 进了 metadata 且实现支持 |

**根本原因**：`EmbeddingStore` 的定位是**向量索引**，而我们的表同时是**权威数据源** ——
要持久化、要按业务键查、要按时间删、要追溯来源（`source_assistant` / `source_session`）。

换成 `EmbeddingStore`，就等于"权威数据在 MySQL，索引在别处"→ **双写**。
新增要写两处、删除要删两处、两者可能不一致，而这类问题的症状是
"检索到的数据其实已经删了"或"新增的搜不到" —— 难查且偶发。

> **对"单用户几十条"这个量级，双写的一致性成本远大于检索性能收益。**

### 理由三：两条业务规则，`EmbeddingStore` 表达不了

**a. 时间倒序重排**

```java
.sorted(Comparator.comparing(UserMemory::getCreatedAt).reversed())
```

因为**一期不做 UPDATE/DELETE**（既有决定），所以库里会同时留着"住奥斯汀"和"搬到西雅图"。
**相关度排序解决不了这个矛盾** —— 两条对"我住哪"的相关度是一样的，只有时间能定序。

`search` 只按 score 排序，没有"二次按业务字段重排"的钩子。

**b. 维度一致性校验 + 跳过**

```java
private boolean isValid(UserMemory memory, float[] queryVector) {
    if (memory.getEmbedding().length != queryVector.length) {
        // 只报一次，然后跳过 —— 因为 dimensions 改过会让老向量全部不可比
        ...
        return false;
    }
    return true;
}
```

`InMemoryEmbeddingStore` 遇到维度不一致会**抛异常** → 整次检索失败，而不是"跳过坏的、用好的"。
**这是行为差异，不是风格差异** —— 一条脏数据会让该用户的所有检索都失败。

### 理由四：外部向量库的代价换不来收益

- **pgvector**：要把 MySQL 换成 PostgreSQL（你现有全部表和 Mapper 都要动）
- **Milvus / Qdrant / Chroma**：新增一个中间件要部署、要监控、要备份
- **Redis + RediSearch**：需要非标准 Redis 模块

而收益是**ANN 索引** —— 它的价值在**百万级向量**上。

我们的场景是**在 500 条里找最相关的 5 条**。线性扫描 500 条余弦 = 微秒级。
**引入索引是为了加速，但这里没有需要加速的东西。**

---

## 3. 诚实的成本清单：我们"自己写"的到底有多少

不能只说"我写的更好"。实际自己写的是两处：

| 自己写的 | 行数 | 替代方案 |
|---|---|---|
| `CosineSimilarity` | ~20 行 | `EmbeddingStore` 内部也有，但要用它就得接受整套抽象 |
| `MemoryRecallService.recall()` 的编排 | ~30 行 | `EmbeddingStoreContentRetriever` |

**其余全部是现成的**：

- 持久化 → MyBatis-Plus（含 `FloatArrayTypeHandler` 处理 BLOB 编解码）
- 向量化 → `EmbeddingModel`（ai-common 的注册器）
- 注入点 → `ContentRetriever` 接口
- 装配 → `RetrievalAugmentor`

**所以准确的说法是**：我们在一个 50 行的地方没有用框架的抽象，
因为**那个抽象解决的是"持有一批向量"，而我们不需要"持有"** ——
我们的向量存在自己的表里，检索只是"顺手算一下"。

---

## 4. 什么时候该换成 `EmbeddingStore` + 向量库

**不是"以后也不会"，是"触发条件还没到"。** 三个信号，出现任一就重新评估：

| 信号 | 为什么它会触发换 |
|---|---|
| **单用户事实数 > 2000** | 线性扫描开始有明显成本（500 条是微秒，2000 条还是微秒，但再往上就该警惕了） |
| **需要跨用户检索** | 比如"所有用户里谁提到过 X" —— SQL 在这个查询上也要建索引，而那是向量库的主场 |
| **你已经因为知识库上了向量库** | **最实际的信号** —— 基础设施已经在，双写的边际成本变低，顺手把用户记忆也放进去 |

**第二和第三条是真正的分水岭。** 第一条其实很难触发（单用户 500 条上限是我们自己设的）。

---

## 5. 真要换的话，最小改动路径

好消息是**切换点已经留好了**：

```java
public interface UserMemoryStore {
    void insert(UserMemory memory);
    List<UserMemory> findByUserId(String userId, int limit);
    int countByUserId(String userId);
    void deleteOldest(String userId, int count);
    void deleteAll(String userId);
}
```

`MemoryRecallService` 和 `UserMemoryRecorder` 都只依赖这个接口。
换实现即可，**上层一行不用改**。

两种换法：

**换法一：只换存储实现**（比如 `PgVectorUserMemoryStore`）——
保留 `MemoryRecallService` 的编排（时间定序、维度校验照旧）。改动最小。

**换法二：连检索一起换**（用 `EmbeddingStore` + `EmbeddingStoreContentRetriever`）——
需要额外处理三件事：
1. `UserMemory` → `TextSegment` 的映射（content 进 text，其余进 metadata）
2. 时间倒序重排在 `retrieve()` 里补回来
3. 维度不一致的处理策略（从"跳过"改成"过滤在写入侧"）

**推荐换法一** —— 它保留了那两条业务规则，而且不引入 `TextSegment` 这层转换。

---

## 6. 一句话总结

> **`EmbeddingStore` 解决的是"持有一批向量并快速检索"，而我们的向量存在自己的表里、
> 每次只检索一个用户的几十条 —— 它解决的那个问题我们没有。**
>
> 换它要付的代价是**双写的一致性**和**两条业务规则的丢失**，
> 换回来的加速在 500 条的量级上是零。
>
> **触发条件是"你已经因为别的需求上了向量库"，而不是"向量检索听起来该用向量库"。**
