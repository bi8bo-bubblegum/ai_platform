# 项目长期记忆 · ai_platform

跨会话保留的项目约定。写在这里的是**已定的决策**，不要重新论证。

## 一、交付方式（强制）

代码**不直接改项目源码**。一律输出到 `docs/code-handouts/` 下的 `.md` 交付单，用户自己敲。
格式：完整文件路径 + 完整代码 + 逐行讲「为什么」+ 检查点命令 + 自查清单。
（跨项目规矩的完整版在 `~/.workbuddy/MEMORY.md`。）

> ⭐ **2026-10-12 加的规则：凡"完整替换一个已有文件"，必须在文首单独列一份
> 「相对上一版，这几行是新增的」差异清单。**
> 起因：30 号让用户替换整个 `ToolExecuteNode`（100+ 行），其中两段是本次的关键新增
> （拒绝分支、`finish()` 收尾），用户在上百行里辨别不出来 → 漏敲 → 审批静默失效。
> **一句话的成本，省一次漏敲。**

> ⭐ **2026-10-13 规则收紧（40 号，同一类错误第 3 次）：**
> **给"替换 / 删除 / 移动"类指令时，必须同时给出"操作完成后的完整状态"。**
>
> 三次的形态：
> | 次数 | 我写的 | 漏掉的 |
> |---|---|---|
> | 30 号 | "完整替换这个文件" | 哪几行是新增的 |
> | 31 号 | "多处按同样方式改掉" | 逐处列出 |
> | **40 号** | "换一个依赖，**其余不动**" | **"其余"是哪几个** → 执行成"只留一个" → 编译失败 |
>
> **共同点：都写了"要改什么"（动作），没写"改完什么样"（目标）。**
> **"其余不动"这四个字，在同时要动"1 换 + 1 删 + 5 留"的文件上完全不够用。**
> → 本单改成**直接给完整的 pom.xml**。

> ⭐ **命名原则：名字只在"需要区分"时才应该不同。**
> 同一个东西在两层用两个名字（state 里叫 `proposedTool`、响应体里叫 `pendingAction`），
> 每次读代码都要多花一分钟确认"这是不是同一个字段" —— 这一分钟就是"容易误导"的来源。

## 二、写作与表达（强制）

- 禁止空话套话（赋能 / 抓手 / 拥抱变化 / 为团队创造价值）
- 能用具体事件就不用定性形容词；**能用数字就不用形容词**
- 先对齐意图，再动手

## 三、给方案的纪律

1. **自己写某个组件之前，必须先在交付单里回答「为什么不用现成的」**，并给出
   「什么情况下该用现成的」的判断依据。只写「我来写」不算方案
2. ⭐ **两阶段：先对齐方案 → 他点头之后才生成交付单。**
   （2026-10-12 用户明确要求，原话："你先和我确定完了再生成文件吧，不然每次白生成"）
   - **① 对齐**：**只在对话里**讲取舍 / 判据 / 清单，**不写文件** —— 目标是让他能拍板
   - **② 产出**：确认后才出 `.md`（含完整代码）
   - **例外**：① 他明确说"只要代码"；
     ② **复核 / 诊断 / 定位类**（只读代码给结论、不含"待实施的新代码"）——
     那是**发现**不是**方案**，问题客观存在、不依赖他的选择，可以直接出
   - **口径**：问自己"这份文件里的代码，需要他做决定之后才能落地吗？"
     需要 → 先讨论；不需要（只是描述现状）→ 可以直接出
3. ⭐ **判据要按问题类型分——这两类问题的结论恰好相反：**
   - **抽抽象**（抽接口 / 基类 / 工厂）→ 判据是**「形状是否已从真实用例里长出来」**。
     第二个用例没出现 → 形状靠猜 → 必返工 → **该等**
   - **定契约**（id 格式 / key 命名 / 数据形状 / 对外接口）→ 判据是**「可逆性」**。
     一旦有存量数据或对外承诺，改它成本随时间上升 → **该趁早**

   > **一句话：抽象要等形状，契约要趁没数据。**

   > **辅助判据（判"该不该抽"时最实用）：数得出来几个使用者？**
   > **同一段逻辑真被重复写了 N 遍 —— 那就不是"提前抽象"，而是"消除已存在的重复"。**
   > 判据从"我猜将来会有第二个使用者"变成**"现在已经数得出来几个"**。
   > （交付单 27 就是靠它一次定出了唯一该抽的那项：`ChatMessage` ↔ 文本被写了 5 遍。）

   本项目里的正反例：
   - ✅ **该等**：`MemoryAwareAssistantFactory`（被否）、`EmbeddingModelRegister`（被劝退）、
     `BaseAgentState`（第二个图的 state 形状未知）、**graph 的记忆节点**
     （依赖 `state.userId()/messages()`，抽它要先定 state 接口，而第二个图的 state 只能猜）
     —— 都属于"形状靠猜"
   - ✅ **该趁早**：threadId / checkpoint key 的命名空间（零数据窗口，见交付单 26）、
     `SessionKey` 的三段格式 —— 都属于"不可逆契约"
   - ✅ **该抽**：`ChatMessageTexts`（5 个类 / 8 个方法重复，见交付单 27）
     —— 属于"重复已经数得出来"

## 四、架构边界（已定，勿重新论证）

**上游已有 ai-gateway**（`http://ai-gateway.tz.com/v1`，OpenAI 兼容协议）。
多厂商统一、凭证收敛、限流计费都在那一层。因此：

- **AI-Common 不做路由、不做限流、不做凭证托管**，只做「配置 → LangChain4j 实例」装配
- 曾设想的 `ai-model-service` 是多余的，已废弃
- AI-Common 是**零基础设施依赖**的（只依赖 LangChain4j；web / micrometer 均 optional）

**token 计量的位置**（2026-10-09 用户已决定）：

**就在 SDK 层做**（AI-Common 的 `ChatModelListener`）。分析过"网关才是天然计量点"，
但用户明确选了这一层 —— 不再劝。

这一层的三条已知限制，已写进交付单 04 的 0.2 节：

1. **流式下 usage 可能拿不到** —— OpenAI 协议流式响应默认不返回 `usage`，
   需 `stream_options.include_usage`，且取决于网关是否透传。**必须先实测**。
2. **拿不到 TTFT（首字延迟）** —— `ChatModelListener` 只有 onRequest / onResponse / onError，
   无 token 级回调；流式 onResponse 只在流结束时触发。TTFT 只能在业务侧
   `TokenStream.onPartialResponse` 第一次回调里记。**这是流式最该看的指标。**
3. **只覆盖 Java 侧调用方**，脚本 / 其他语言统计不到。

**设计约束**：`ModelUsage` 必须有 `estimated` 布尔字段 —— 准确值和估算值不能混在一个字段里，
否则报表对不了账。估算用 `OpenAiTokenCountEstimator`（jtokkit，借 OpenAI 词表，
中文偏差偏大，只能做相对比较）。

**模块划分**：`AI-Common`（模型 + 可观测）← `AI-Memory`（记忆）← `assistant-*`
判断依据：*删掉它，是不是所有助手都要各自实现一遍？是 → 归 common 类模块。*
记忆属于「执行编排」，不属于「依赖能力」，所以单独一个模块。

**AI-Memory 关键决策**：
- 会话记忆（episodic）用 Redis，TTL 滑动刷新；摘要 TTL ≥ 消息 TTL
- 用户记忆（semantic）用 MySQL 事实表，`embedding` 存 BLOB（4096B vs JSON 约 12000B）
- **用户级记忆必须跨助手共享同一 schema** —— 否则用户画像碎裂，「用户级」名存实亡
- **摘要用单线程池（读改写必须串行）；事实抽取用多线程池（纯追加可并发）**
- memoryId 格式固定为 `userId::sessionId`，让 `ChatMemoryProvider` 和
  `ContentRetriever` 都能拿到 userId，避免 ThreadLocal（异步路径会静默失效）
- **一期不做 UPDATE/DELETE**，只做去重（相似度 ≥ 0.95 丢弃）。误删比漏记严重得多
- **向量检索不用 `EmbeddingStore`**（2026-10-10 正式论证，**勿重议**，详见交付单 23）。
  先说清：**用了** `EmbeddingModel` / `ContentRetriever` / MyBatis-Plus，**只有**向量存储+检索这一小段没用。
  三条理由：① **候选集量级** —— SQL `where user_id = ?` 在算余弦**之前**过滤，
  候选集恒定（≤500 条）；而 `InMemoryEmbeddingStore` **无索引、线性扫描**，
  候选集是全平台事实总和（**实测验证**）。数据库的 WHERE 替我们承担了"向量库索引"的职责。
  ② `UserMemoryStore` 5 个操作里 **4 个对不上**（它是索引，我们的表同时是权威源 → 换它 = 双写）。
  ③ 两条业务规则表达不了：**时间倒序重排**（一期不删改，冲突事实只能靠时间定序）、
  **维度不一致跳过**而非抛异常（后者会让一条脏数据搞挂该用户全部检索）。
  **触发条件**：单用户事实数 >2000 / 出现**跨用户检索** / **已因知识库上了向量库**（最实际）。
  **切换点**是 `UserMemoryStore` 接口，换实现即可。推荐只换存储实现，保留编排。
  ⚠️ 本机 `.m2` 里有 `langchain4j-pgvector`（别的项目留下的），**本项目从未引用**

**AiServices 的装配归属**（2026-10-09 用户拍板）：

**`AiServices.builder(...)` 写在业务模块自己**（如 `assistant-demo/.../config/AssistantConfig.java`），
**AI-Memory 只提供记忆能力**（`ChatMemoryProvider` / `ContentRetriever` / `RetrievalAugmentor` 这些 Bean）。

废弃方案：AI-Memory 里的 `MemoryAwareAssistantFactory`（已从交付单 04/06 删除）。
理由（写在 04 的 2.2 节）：装配器是跨模块的**接缝**，住在被粘的某一方里会持续腐化；
每加一个 `AiServices` 能力（tools / contentModerator / hallucinationStrategy）都要改
AI-Memory 的公共 API。**它不是"助手底座"，是"记忆模块"，只导出能力，不导出装好的助手。**

配套：用 `AssistantWiringTest` 两个结构断言（假 `ChatModel` + 数消息条数）替代
"封装防漏"——**测试比封装便宜，且不限制加工具**。
将来真有第三个助手的装配高度相似时，抽出来的会是"助手装配"层（带工具注册），
**不是当初那个形状**。

**流式返回类型 = `TokenStream`（已定，勿重新论证）**：

技术栈是 Spring MVC + `SseEmitter`（无 WebFlux）。理由（详见交付单 04 的 4.2 节）：

1. 官方定位：`TokenStream` 与同步 API 是**稳定契约**；`Flux`/`CompletableFuture`/`Flow.Publisher`
   这几种返回类型标了 **`@Experimental`**
2. **`Flow.Publisher` 是冷流 —— 「每一次订阅都会开启一次新的交互」**，订阅几次就是几次模型调用；
   `TokenStream` 是显式 `.start()`，调一次就一次
3. **`TokenStream` 事件最全**：`onPartialThinking` / `beforeToolExecution` / `onToolExecuted` /
   `onRetrieved`。项目已引 `ai-common/mcp/McpClientRegister`，接工具后 `onToolExecuted` 是唯一能
   看到"调了哪个工具、参数是什么"的地方，`Flux<String>` 会全丢
4. 返回 `Flux` 会迫使 `SummarizingChatMemory` 实现 `addAsync`/`setAsync`/`messagesAsync`
   （默认抛 `AsyncNotSupportedException`；漏了是"能流式但记不住"且不报错）

**已知现状**：`reactor-core:3.7.19` 已在 classpath（Redis starter → lettuce 带入），
但工程**没有** `langchain4j-reactor`、没有 WebFlux。要翻 Flux 只需加前者。

**只有两种情况该翻**：整个应用切 WebFlux；或要做"多助手并发回答 + `Flux.merge` 合并成一个流"。

**持久层统一用 MyBatis-Plus（已定）**：

`ai-common` 里已有 `mybatis-plus-spring-boot3-starter:3.5.17` + `AiTokenUsageEntity`/`AiTokenUsageMapper`/
`TokenUsageDbAutoConfiguration` 这一整套可照抄的范式。**不要手写 JDBC/JdbcTemplate**。

- **实体必须是 `class` + `@Data`，不能用 record。** 实测：MyBatis 3.5.19 的 `Reflector` 有
  `isRecord`/`addRecordGetMethods`，record 的**读**没问题；但 `hasSetter` 为 false、
  `setValue` 抛 `ReflectionException` —— 而 MP 的 `IdType.AUTO` 要回填主键、`updateById` 要可变实体。
- **`@TableName(autoResultMap = true)` 不能省**：不写则 MP 自动 resultMap 不带 typeHandler，
  症状是「能存进去、读不出来」。`@TableField(typeHandler=...)` 在 insert 和 select 两条路都生效
  （MP 3.5.17 的 `SqlScriptUtils.safeParam(String,String)` 会把它拼进 SQL）。
- **可选数据库功能的自动装配三件套**（照抄 `TokenUsageDbAutoConfiguration`）：
  `@AutoConfiguration(after = DataSourceAutoConfiguration.class)`
  + `@ConditionalOnClass({BaseMapper.class, DataSource.class})`
  + `@ConditionalOnBean(DataSource.class)` + `@MapperScan(...)`。
  **`after` 那一项漏了会让条件永远为 false、整块静默不装载**（不报错，只是 Bean 不见了）。
- Mapper XML 放 `src/main/resources/mapper/<包名路径>/*.xml`（MP 默认扫 `classpath*:/mapper/**/*.xml`）。

**两个记忆的作用域（已定，勿混）**：

| 记忆 | 回答的问题 | 作用域 | 存储 |
|---|---|---|---|
| 会话记忆 | 这次对话刚才聊了什么 | **助手 × 用户 × 会话** | Redis，TTL 滑动 |
| 长期记忆 | 用户是谁 | **用户**（跨助手共享，刻意） | MySQL 事实表 |

**`memoryId` 的形状 = `assistantId::userId::sessionId`**（三段，双冒号分隔）。
`assistantId` 那一段是必须的：`sessionId` 由调用方生成，没有机制保证全局唯一；
少了它，两个助手收到相同 `(userId, sessionId)` 就落进同一个 Redis key、共用一份历史。

- 解析只找**前两个** `::`（第三段往后全归 sessionId，允许 sessionId 含 `::`）
- 前两段不允许含 `::`，在 record 的**紧凑构造器**里就抛异常
- `SessionKey` 的调用点只有两处：控制器拼 memoryId、测试。解析侧都走 `SessionKey.parse`

**Redis key = `<prefix><assistantId>:<sha256(userId)>:<sessionId>`**，构造点唯一收在
`SessionRedisKey`（`ai.mem.session:` / `ai.mem.summary:` 两个前缀共用同一套规则）。
assistantId / sessionId 明文（非个人信息 + 排查要认得出），只有 userId 脱敏。

> ⚠️ **踩过的真 bug**：`RedisSessionSummaryStore` 曾把 memoryId 直接拼进 key（`ai.mem.summary:u123::s456`），
> 明文 userId 进了 Redis。**根因是参数名叫 `sessionId` 而它承载的是 memoryId** —— 名字骗了实现者。
> 所以那条链路上的参数名统一改成 `memoryId`（`SessionSummaryStore` / `ConversationSummarizer`）。

**`ai_user_memory` 必须有 `source_assistant` 列**：事实跨助手共享是刻意的，但必须能追溯
"这条是谁写进来的"，也是将来做"按助手过滤"的落点。同理由，
`UserMemoryRecorder.recordAsync(String memoryId, List<ChatMessage>)` 只收 memoryId，
`assistantId` / `userId` / `sessionId` 由它自己解析 —— 少传两个参数就少一处传反的可能。

**模型注入规则（已定，勿两种写法混用）**：

**只有装配类碰 `ChatModelRegister` / `StreamingChatModelRegister` / `EmbeddingModelRegister`；
其它一切业务类只收解析好的实例**（`ChatModel` / `StreamingChatModel` / `EmbeddingModel`）。

```java
// 装配类：解析在这里做
return new LlmConversationSummarizer(chatModelRegister.getDefault(), summaryStore);

// 业务类：只收实例，不认识 register
public LlmConversationSummarizer(ChatModel chatModel, SessionSummaryStore summaryStore) { ... }
```

理由（写在交付单 04 第三节）：
1. `ChatModel` 是 **LangChain4j 接口**，单测 `new FakeChatModel()` 一行；
   `ChatModelRegister` 是**具体类**（`create` 是 protected abstract，构造器还要 `ChatModelProperties`），测试里造它很别扭
2. 业务类的真实需求是"一个模型"，不是"一个按档取模型的工厂"——选档权属于装配层
3. `profile` 这个词只出现在配置类与装配类，搜起来干净
4. 与 AI-Memory 里 `MemoryRecallService` / `UserMemoryRecorder` / `LlmUserMemoryExtractor` 的写法一致

**配套结论：`ModelRegister.refresh()` 一期不接线。**
`AiServices` 是 `.build()` 那刻绑定模型的，所以"让业务类调用时去 register 取"**解决不了模型热更新** ——
真接 Nacos 时的正确做法是发模型变更事件、由 `AssistantConfig` **重建 `AiServices` Bean**。
在那之前不要把 register 塞给业务类"备用"。

### 接一个新助手模块（写 3 个文件 + 3 个坑）

**你只写 3 个文件**：助手接口（`TokenStream chat(@MemoryId ...)` + `String ask(...)`）、
`AssistantConfig`（`AiServices.builder` + `.chatMemoryProvider(...)`）、Controller（SSE + `SessionKey`）。
**最快开工方式：复制 `assistant-demo`，替换包名 / 类名 / 端口 / 服务名。**

配置六段：`spring.application.name`（= assistantId，**全局唯一**）、`spring.datasource`（必需 ——
ai-common 硬依赖 mybatis-plus，会激活 DataSource 自动配置）、`spring.data.redis`、
`spring.mvc.async.request-timeout`（**必须 ≥ SSE 超时**）、`ai.chat` + `ai.embed`、`ai.memory`。
完整清单见交付单 10。

> 📌 **这六段里只有 `name` 是需要手写的 —— 其余将由 22 号的配置分层方案提供**
> （模块自带 `xxx-defaults.yml` + 服务侧 `spring.config.import`）。
> 方案落地后新建服务只需 `port + name + import`。
> 另：若做了「依赖边界」那条（mybatis-plus 标 optional），`spring.datasource` 也不再"必需"。

**三个坑（都在真实代码里）：**

1. **`UserMemoryRecorder` 是条件 Bean**（受 `ai.memory.user.enabled` 控制）。
   直接构造器注入它，一旦关掉用户记忆 → **整个服务启动失败** → 用 `ObjectProvider<UserMemoryRecorder>`。
2. **`userId` 有两套来源**：会话/长期记忆走 query 参数 `?userId=`；
   token 台账的 `user_id` 走请求头 `X-User-Id`（`AiUserContextInterceptor` 读）。
   只传 query 参数 → 台账里全是 `anonymous`。**新模块开工前必须定一条**
   （推荐 Controller 里调 `AiUserContext.set(userId)`）。
3. **`TaskExecutor` 是隐式依赖**：`userMemoryRecorder` 需要它，但无任何显式声明，
   来自 Boot 的 `TaskExecutionAutoConfiguration`（`applicationTaskExecutor`）。
   自己定义 Executor Bean 时要留意别把它顶掉。

**体感提醒**：`SummarizingChatMemory.add()` 里摘要是**同步**生成的 ——
第 21 轮（窗口刚满）那次请求会明显变慢，因为要等一次额外的模型调用。
想异步得换 `ConversationSummarizer` 实现。

### 三个挂载点，三个静默失效（接助手模块的完整心智模型）

整个接入只有三个"开关"，**全部是往 `AiServices` 上挂东西**，不挂就什么都不发生，且**都不报错**：

| 开关 | 挂在哪 | 漏了的症状 | 有扩展点吗 |
|---|---|---|---|
| `.chatMemoryProvider(...)` | `AssistantConfig` | 不记得上一句 | ✅ LangChain4j 提供 |
| `.retrievalAugmentor(...)` | `AssistantConfig` | 长期事实注入不进去 | ✅ LangChain4j 提供 |
| `recorder.recordAsync(...)` | **Controller 手动调** | 长期事实永远不增长 | ❌ 没有扩展点 |

**为什么"写"没有扩展点**：LangChain4j 的 hook 全在**调用链内部**
（`ContentRetriever` / `ChatMemoryStore` / `ChatModelListener`），
而"这轮对话值不值得长期记住"是**业务判断**，框架不知道也不该知道。

**措辞纪律**：说"手动**触发**"，不说"手动**抽取**" ——
抽取/去重/向量化/落库/容量清理全在 AI-Memory 里，业务侧只写那一行 `recordAsync`。

**推论**：三者静默失效 → **"助手不记得上一句"和"长期记忆一条没写进去"，日志上都像正常。**
所以结构断言不是可选项。写入那一路目前**没有测试覆盖**（加固办法：把 Controller 里那段
抽成 `MemoryWriteback` 组件再单测）。

## 五、约束与现状

- **项目不是 git 仓库**（有 `.gitignore` 无 `.git`）。用户说「先不管 git」，
  当前所有改动**没有回退点**
- 用户是 Java 后端，**要边写边理解**，所以交付单要讲「为什么」而不是「是什么」
- 用户会逐篇 review 并追问，`ChatMemory` 接口签名就是这么被抓出来的

### 🔴 两个待修真 bug（2026-10-10 目录结构复核时发现）

**① `McpAutoConfiguration` 没进 `.imports` → MCP 整体失效。**
`ai-common` 有三个 `@AutoConfiguration` 类（`config` / `metrics` / `mcp` 各一个），
`.imports` 只列了两个 → yml 写 `ai.mcp.enabled: true` 也不生效。
根因是**装配点随包分散**。修法：抄 ai-memory 的模式，1 个外层类 + 嵌套 static `@Configuration`，
`.imports` 永远 1 行。⚠️ 嵌套类失去类级 `after`，必须给**外层**加
`@AutoConfiguration(after = DataSourceAutoConfiguration.class)`，否则
`@ConditionalOnBean(DataSource.class)` 求值过早、整块静默不装载。

**② `StreamingChatModelRegister` 没挂 `TokenUsageListener` → 流式调用不计费。**
`ChatModelRegister` 收 `(properties, serviceName, recorder)` 并挂 listener；
`StreamingChatModelRegister` 只收 `(properties)`。而流式是主场景（`ChatStreamController`），
导致 `ai_token_usage` 只有同步调用的记录。

**排查线索（通用）**：同一个包里两个"平级"实现，**形状不同就一定有漏**。

详细分析（含 P1/P2 分级与"为什么不动 P2"）见 `docs/code-handouts/12-目录结构优化建议.md`，
**改后完整代码**见 `13-改动清单-装配集中与流式计量.md`（12 个动作：5 改 / 3 新建 / 2 删）。

**⚠️ 合成一个装配类会带来一处行为变化（已在 13 里标注）**：原 `AiCommonAutoConfiguration`
有**类级** `@ConditionalOnProperty("ai.chat.enabled")`，管住类内所有 Bean → 关 chat 会连带
关掉 `EmbeddingModelRegister` 和 `AiUserContextInterceptor`（token 台账在独立类里，不受影响）
→ "关一半留一半"。类级条件必须拆掉、**下移到具体 Bean**。

**两个未落的遗留**（不在本次改动内，避免回退时混淆）：
- `ModelRegister.DefaultProfile()` 大写 D 违反命名惯例（应 `defaultProfile()`），改它要动 5 处
- `ModelRegister.get(profile)` 的 **null 守卫始终没落地** —— profile 为 null 时
  `ConcurrentHashMap.computeIfAbsent(null, ...)` 抛 NPE（交付单 01 写过，代码里没有）

### 🔴 另外两个待修真 bug + 四个隐患（2026-10-10 第二轮审核）

详见 `docs/code-handouts/14-代码审核-第二轮.md`。**最紧要的两条：**

**③ `DbUserMemoryStore.deleteOldest` 把主键当用户列匹配 —— 会删错数据**

```java
userMemoryMapper.delete(new LambdaQueryWrapper<UserMemory>()
        .in(UserMemory::getUserId, ids));   // ❌ ids 是主键，应为 UserMemory::getId
```

实际 SQL = `DELETE ... WHERE user_id IN (101,102,103)`。userId 是 UUID 时**一条都删不掉**
（容量控制失效）；**userId 恰好等于某个 id 值时那一行被误删**。潜伏期极长（只在超过
`maxFactsPerUser`=500 时触发），且 `mapper.delete()` 返回值没核对。

**④ `ChatAssistantTest` 没重写**（11 号报告给过重写版，未落地）
`memoryId = "u1"` → `SessionKey.parse` 必抛异常；`chat()` 返回 `TokenStream` 但**没 `.start()`**
（惰性，什么都不发生）；零断言；`@SpringBootTest` 要真 MySQL+Redis+网关。

**四个 P1 隐患**：① `UserMemoryRecorder.isDuplicate` 没过滤维度差异 → 库里一条脏向量
让该用户新事实**永久写不进去**（`MemoryRecallService` 那边过滤了，不对称）；
② `ChatModelProperties.endpoints` 缺 `@DefaultValue` + `getEndpoint` 只判 `isEmpty` 没判 null
→ 漏配时 NPE 而非可读报错；③ `UserMessage.singleText()` / `AiMessage.text()` 在多模态与
工具消息上抛异常/null，两个 `render` 都直接调 → **接 MCP 后摘要会频繁静默失败**；
④ 抽取提示词是旧版本（交付单 05 已改、代码没同步），缺「跨助手共享」判据。

**一条通用排查线索**：同一职责的两处实现若**形状/防护不对称**，就一定有一处漏了。
本轮靠它发现了 `StreamingChatModelRegister` 缺 listener、`isDuplicate` 缺维度过滤、
`render` 只护了 `text()` 没护 `singleText()`。

### 🔴 第三个待修 P0 + 三个 P1（2026-10-10 第三轮审核）

详见 `docs/code-handouts/15-代码审核-第三轮.md`（末尾有**三轮累计遗留清单**）。

**⑤ 流式调用的 token 台账拿不到 `user_id` —— 但【能修好，不需要网关】。**

**问题**：`TokenUsageListener.onResponse` 里用 `AiUserContext.get()`（ThreadLocal）取用户，而
**流式回调跑在模型自己的 I/O 线程上**（OkHttp 回调线程），不是请求线程 → 必然读到 null。
同步调用读得到、流式读不到 —— 而且这个坑**只在修好"给流式挂 listener"之后才暴露**。

**✅ 正解（实测验证过，改动只有 15 行）：给 `TokenUsageListener` 补 `onRequest`，在它里面快照。**

三条实测依据（反编译 `langchain4j-core-1.20.0.jar` + `langchain4j-open-ai-1.20.0-sources.jar`）：
1. **`StreamingChatModel.chat(ChatRequest, handler)` 是接口的 default 方法**，字节码显示它
   **先**触发 `ChatModelListenerUtils.onRequest`、**再**调 `doChat` 发起异步 HTTP。
   而 `chat()` 是在**调用线程**上执行的（业务调 `TokenStream.start()` 那一行）→
   **`onRequest` 在调用线程上，读得到 ThreadLocal** ✓
   （`OpenAiStreamingChatModel.doChat()` 里**完全不触发 listener**，listener 全由接口 default 方法触发
   —— 所以这是框架保证，不是某个 provider 的实现细节。）
2. `attributes` 是 `new ConcurrentHashMap<>(options.listenerAttributes())`，**跨线程安全**，
   且**同一个实例**在 onRequest/onResponse/onError 之间传递 ✓
3. `OpenAiStreamingChatModel` 第 197 行已自动带 `.streamOptions(StreamOptions.builder().includeUsage(true).build())`
   —— **1.20 已自动请求 usage**，不需要手动配（修正了 04 号文档"需要手动带"的说法）

**规则**：**onRequest 里快照，onResponse 里从 attributes 取，不碰 ThreadLocal。**
这一条同时修好同步和流式两条路径。

> ⚠️ 15 号里"只能交给网关 / `user_id` 一致写 anonymous"的结论**已被推翻**，别按那个做。
>
> 「给 `ChatModel` 套装饰器」也能work（把 userId 闭包进匿名 handler 跨线程传递），
> 但要多写两个类（`ChatModel` + `StreamingChatModel` 各一个）并转发 6-7 个接口方法，
> 换来同样效果，**不划算**。
>
> 另：`AiUserContext.wrap/open` 是死代码（全项目零调用者）—— 它是为"从当前线程 fork 的任务"
> 准备的，救不了模型 I/O 线程。

**三个 P1**：① `RedisSessionMemoryStore.getMessages` 反序列化失败无降级 → 脏 key 让该会话
**永久不可用且无法自愈**（应 catch + 清 key + 返回空历史）；② **`ai.embed.timeout: 60s` 对请求路径上的
检索太长**（检索在调主模型之前同步执行，embedding hang 住 → 用户等 60 秒）→ **改成 5s**，
这是"改一行消除一个故障模式"；③ **`userId` 来自 query 参数 → 越权读他人记忆/长期画像**，
上生产前必须改成来自认证上下文（需网关覆写 `X-User-Id`）。
⚠️ 注意：14 号那条「userId 两套来源」的**选项 B（Controller 里 set）不解决这个安全问题** ——
它只让两处一致，userId 本身仍是请求参数。

**两个观察（只记录，不改）**：`SummarizingChatMemory.add()` 是 read-modify-write 且中间夹着
几秒的 LLM 调用（摘要），同 memoryId 并发会覆盖丢消息 —— 正常前端不触发，真修要分布式锁，
且 LangChain4j 的 `ChatMemory` 契约本身不承诺并发安全；`SessionRedisKey` 的 sha256 无盐 +
截断 8 字节，低熵 userId 可枚举反推（合规问题，非外网可攻破）。

### ⑥ 长期记忆链路有 5 个静默降级点（2026-10-10 用户报障"换 session 后不知道我是谁"）

**用户现象**：同 session 内正常（会话记忆好），换 sessionId 后助手不记得他是谁（长期记忆失效）。

**链路上的 5 个静默点** —— 它们的**共同表现都是"检索结果为空"**，导致五种不同故障在日志里
长得一模一样（助手"想不起来"）：
1. `AssistantConfig` 里 `augmentorProvider.getIfAvailable()` 返回 null → 静默不挂检索
2. `MemoryRecallService` 的 `isValid` 维度不符 → 静默过滤（只 error 一次）
3. **`min-score` 阈值过滤 → 完全静默**（最可能的原因，见下）
4. `recall()` 里三处 `return List.of()`（userId 空 / query 空 / store 空）
5. `catch (Exception e) { log.error("失败"); }` → **连堆栈都没有**

**✅ 已实测确认（2026-10-10）—— `min-score: 0.60` 就是元凶。**
用户实测：库里存了「用户叫小明」，问「你知道我是谁吗」在 **0.6 下查不到、改成 0.3 就查到了**。
→ **建议阈值 0.3**（不是 0.35，实测 0.3 有效）。

**为什么 0.6 在这个场景下真的算高**（用户反问"0.6 也不算高啊"，这是关键认知）：
**余弦相似度只在同一个模型内有相对意义，它的取值分布很窄，不是均匀的 0~1。**
对 `text-embedding-3-small` 这类模型，实测「无关问句」约 0.05~0.25、「相关问句」约 0.28~0.50 ——
**0.6 在这两段之外**，所以怎么问都检索不到。

三个具体原因：
1. **余弦相似度衡量的是"表述相似"，不是"语义相关"。** "你还记得我是谁吗" 和 "用户叫小明"
   逻辑上强相关，但表述毫无重叠
2. **人称错位**（最具体）：问句用第一人称"我"，而事实被强制写成第三人称"用户"（抽取提示词
   要求"以「用户」开头"）→ 这两个词的向量隔得远，直接压低分数
3. **文本短 → 方差大**。"用户叫小明"只有 6 个字，没有上下文可借力

**注意：这修正了"RAG 阈值 0.6~0.7 是常规值"的直觉** —— 那个直觉来自长文档 chunk 检索
（chunk 和 query 有词汇/主题重叠），而这里的事实是**极短的陈述句**，量纲完全不同。

**待验证的改进假设**（先别改，用 `/debug/memory/compare` 跑数据再定）：
如果把事实存成第一人称（"我叫小明"）而不是"用户叫小明"，与第一人称问句的相似度应该更高。
但改人称会与已有数据不一致（老数据第三人称），所以**先测再定**。
更彻底的方向是 query 改写（把问句改成"用户的身份信息"再检索，LangChain4j 有
`CompressingQueryTransformer`），但要多一次模型调用，**一期不做**。

**必须修的 bug（可观测性）**：`MemoryRecallService.recall` 的
```java
} catch (Exception e){ log.error("失败"); return List.of(); }
```
没传 `e`，堆栈全丢，文案无上下文 —— 这是排查该类问题的直接障碍。改成带 `userId`/`query`/`e`。

**诊断工具**：见 `docs/code-handouts/17-长期记忆不生效诊断.md` ——
含四步定位法、一个临时诊断端点（一次输出 augmentorPresent / factCount /
embeddingDimensions / top5Scores / passedThreshold，五个原因一次区分）、
以及 `MemoryRecallService` 加 debug 打分日志的完整代码（阈值问题一跑就看得见）。

### ⑦ 用户自修的两处（都准）+ 一个新风险（2026-10-10 复核）

**用户自修（都正确）：**
- **`TokenUsageListener.onRequest` 加 null 兜底 —— 这是我漏掉的真 bug。**
  `attributes` 是 `ConcurrentHashMap`，**不允许 null value**，`put(k, null)` 抛 NPE；
  而这次调用发生在**框架的 `StreamingChatModel.chat()` 里**，会让**整个模型调用失败**。
  而"非请求线程读 ThreadLocal 得 null"恰是常态（后台抽取任务）。
- **`UserMemoryRecorder.recordAsync` 用 `try (AiUserContext.Scope s = AiUserContext.open(userId))`
  包住异步任务** —— 写法正确（try-with-resources 保证异常路径也恢复 ThreadLocal），
  修好了抽取调用的 token 归属。**修正我第三轮的判断**：`wrap/open` 对"从请求线程 fork 到
  线程池的任务"正是主场，只是救不了模型 I/O 线程。
- `ChatStreamController` 的 userId 改从 `AiUserContext.get()` 取（不再用 query 参数）→
  会话/长期/台账三处同源。

**🔴 但这带出一个新风险：匿名用户共用记忆空间。**
`AiUserContextInterceptor` 把缺失的 userId 兜底成 `anonymous`，而 memoryId 里带 userId →
**所有不带 `X-User-Id` 的请求共用同一份会话历史和长期记忆**
（A 说"我叫小明"，B 问"我是谁"会答"小明"），且数据污染后很难发现。
**修法：userId 为 null/blank/anonymous 时抛 401（快速失败），不要静默放行。**

**⭐ 关键区分（同一个常量，相反的处理）**：

| 用途 | anonymous 该怎样 | 理由 |
|---|---|---|
| **token 台账** | **照记** | 统计性质：宁可记"匿名用量"，不要丢记录 |
| **记忆（会话+长期）** | **拒绝 401** | 隔离性质：宁可拒绝，也不能让两人数据串在一起 |

**清理项**：`TokenUsageListener.onResponse` 有个没用的局部变量 `String userId = AiUserContext.get();`
（误导后来人以为在用 ThreadLocal）；`UserMemoryRecorder.embed(String)` 成死方法；
两个多余 import（`EmbeddingModelRegister` / `UserMessage`）；
`MemoryRecallService` 的 `log.error("失败")` **仍未改**（17 号就提了）。

### ⑧ 鉴权边界：userId 的可信性取决于网关，不取决于"是否登录"（2026-10-10）

用户提出"后期必须登录才能访问，是不是就不会有匿名问题了"。**部分成立，但漏了更要紧的一层：**

| # | 问题 | "必须登录"能解决吗 |
|---|---|---|
| 1 | 未登录能访问 → 兜底 anonymous → 互串记忆 | ✅ 能（前提：所有流量必经网关） |
| 2 | **登录后伪造别人的 `X-User-Id` → 越权读他人记忆** | ❌ **不能** |

**问题 2 的答案在网关侧**：`X-User-Id` 是**覆写**（从 token 解析后覆盖）还是**透传**（原样转发）？
- 覆写 → 客户端伪造无效 ✓
- 透传 → **登录了也能读别人的记忆**，而且加 401 检查**无用**（userId 看着完全合法）

**自测方法**（不必等网关的人）：带自己的 token + 伪造 `X-User-Id: not-my-id` 打一次请求，然后
```sql
SELECT DISTINCT user_id FROM ai_token_usage ORDER BY id DESC LIMIT 5;
```
出现 `not-my-id` → 透传（安全问题成立）；出现真实 ID → 覆写（通过）。
用台账查是因为它**每次都写**，比记忆（Redis TTL / 异步抽取）快。

**要跟网关确认的四件事**（19 号文档有可原样转发的清单）：
① `X-User-Id` 覆写还是透传（**阻塞项**）；② 未鉴权请求网关是 401 还是放行到后端；
③ 有没有绕过网关的路径（集群内直连 Service / 定时任务 / 其他服务调用）；
④ 能否加一个**网关覆写的"已鉴权"标记头** —— 后端只信任网关覆写的头，比"检查 anonymous"更彻底。

**"现在内部服务、后期再说"的风险窗口**：会话记忆串了**可逆**（Redis TTL 48h 自动消失）；
**长期记忆串了不可逆** —— 永久保存且**事后无法区分归属**（脏数据和正常数据长得一样，
都是"用户在 XX 部门"这种句子），只能整条删掉让用户重说，而"发现异常"靠用户抱怨。

**落地方案：配置开关，默认严格**（`ai.memory.require-user-id: true`，本地开发设 false）。
3 行代码把"静默污染数据"变成"明确报错"，且不阻塞任何事。

**⭐ 本轮提炼：纵深防御。** 网关鉴权是**第一道**防线，服务自身校验是**第二道**。
**"第一道存在"不构成"第二道不需要"的理由** —— 因为第一道在**运维层面**可被绕过
（网关漏配路由、内网 curl、集群内调用），而第二道在**代码层面**不可绕过。

**📌 当前决策（2026-10-10 用户决定）：暂缓处理鉴权，接真实鉴权时再说。**
理由：现在没有真实用户数据，anonymous 混用只发生在自己的调试请求之间。
**推荐的补救形态**（19 号 §8）：不改拦截逻辑，加一个 `MemoryGuardStartupCheck`
（`@PostConstruct` 打一段 warn），**把待办放进每次启动的日志里**，而不是放在脑子里。
处理完删掉这个类。

**⚠️ 触发条件（出现任一就必须回来做）**：
① **第一个真实用户接入之前**；② 服务给团队外部的人访问之前；③ 内网开放访问之前。
判据核心一句话：**除了你之外的任何人的数据，有没有进过 `ai_user_memory` / `ai_token_usage`？**

**处理顺序不能反**：① 先确认网关是覆写还是透传（透传的话后端改代码没用）
→ ② 再打开 `ai.memory.require-user-id` → ③ 删掉启动检查类。
**反了的话**：打开 require-user-id 只会拦住没带头的请求，带伪造头的照样通过 ——
你会以为已处理，但实际没有。

**全链路 token 覆盖情况**：主对话（同步/流式）✅、摘要 ✅、事实抽取 ✅（用户新修）、
**向量化（写入/检索）❌** —— **建议一期不补**：cost 量级差 10~100 倍、provider 多半不返回
embedding usage（只能记次数，与 token 台账语义不一致）、要新增 `EmbeddingModelListener`
+ 改 register + 可能改表结构。**若目的是"发现异常"而非"成本核算"，用 Micrometer Timer
比走 token 台账划算。**

### 🎯 底座盘点（2026-10-10 用户明确方向：先搭底座，不看具体业务）

用户否掉了"建第二个助手来验证抽象"这条路。完整分析见 `docs/code-handouts/20-AI底座盘点-边界契约与缺口.md`。

**进底座的判据（三条必须同时成立）**：① 所有助手都需要 ② 与业务语义无关 ③ **有独立的技术复杂度**。
第 3 条最易忽略 —— 几十行代码的能力做成模块是负担。

按判据筛：模型接入 / 记忆 / 上下文 / 可观测 = 合格；
**提示词不够格**（难度在"写什么"不在"怎么存"，做成模块 = 给配置文件穿西装）；
**工具合格但特殊**（机制通用，"挂哪些工具"是业务决定 → 底座交付"怎么被接进来"）。

**底座六层现状**：上下文缺 4 维 / 模型接入 完整 / 记忆 完整 / 工具 未闭环 / 可观测 半张表 / 提示词 空白。

**三个真实缺口（建议顺序）**：
1. **依赖边界**（10 分钟零风险）：`mybatis-plus-spring-boot3-starter` 与 `langchain4j-mcp` 在
   `ai-common/pom.xml` **没有 `<optional>`**。mybatis-plus 会带进 `spring-boot-starter-jdbc`
   → 激活 `DataSourceAutoConfiguration` → **引 ai-common 的服务不配 datasource 就起不来**
   （新助手清单里"spring.datasource 必需"的根因，那是可消掉的约束不是约定）。
   **代码早就把条件门装好了**（`@ConditionalOnClass({BaseMapper,DataSource})` /
   `@ConditionalOnProperty("ai.mcp.enabled")`），**只差 pom 声明，代码一行不动**。
2. **上下文扩容**：`AiUserContext` 只有 userId，实际有**五个身份**
   （userId / assistantId / sessionId / traceId / tenantId），散在 ThreadLocal / `@Value` /
   方法参数 / 不存在 四处。证据：`SessionKey.of(assistantId, userId, sessionId)` 一行三维度三来源。
   → 扩成 `AiCallContext`：`userIdOrAnonymous()`（兜底）与 `userId()`（区分"没有"与"匿名"）并存、
   `withUserId()` **覆盖式**（后台任务只换 userId、其余继承）、`Snapshot` 不可变（要跨线程进 attributes）。
3. **可观测补全**：`TokenUsageRecord` 只有 token。**最要命的是 `onError` 的 token 三字段全 null，
   而"成功但网关没返回 usage"也是 null —— 两种情况在表里长得一样。**
   加 `success` / `latencyMs` / `traceId` / `errorType`（**只留异常类名不留 message**，message 常含隐私）。
   `traceId` 进 attributes 同样要兜底 —— **attributes 不接受 null**（userId 那个 NPE 的同坑）。

**⭐ 现在不要拆模块**（`ai-common` 虽是杂物间）：① **缝还没显形**（两层空、一层未闭环，下刀会切错）
② **拆是物理重构（成本高）、定契约是逻辑调整（成本低），后者拆不拆都要做**；
③ 改包名要连带 `@MapperScan`、mapper XML 的 `namespace`/`typeHandler`/`type`、
`resources/mapper/` 镜像目录 —— **编译期全不校验**（与"结构备注"一致）。

**✅ 契约强度已定（2026-10-10 用户拍板）：保持弱契约。**

底座**给 Bean、不给框架**；业务自己写 `AiServices.builder()`。含义：
- **底座交付的是「Bean + 文档」**，文档是底座的一部分不是副产品
- **每个装配点必须有结构测试兜底**（会话记忆 / 长期检索 / 工具 / 将来审核）——
  这是弱契约的**安全带**，没有它弱契约从"灵活"变"危险"
- `AssistantWiringTest` **不是测试，是契约的一部分**
- 验收标准：**一个新助手接入时，要写的代码和要踩的坑都收敛到一份清单里**

**通用模式（弱契约下每个接入点都长这样）**：
**机制进底座，策略归业务** —— 底座管"怎么接进来"，业务管"接哪个、什么阈值"。
| 接入点 | 底座提供 | 业务提供 |
|---|---|---|
| 记忆 | `ChatMemoryProvider` Bean | 要不要挂 |
| 检索 | `RetrievalAugmentor` Bean | 挂哪些 retriever |
| 工具 | `ToolProvider` 门面 | 挂哪些工具 |

底座的活 = **"把可选项变成可注入的 Bean，并保证不挂也不会炸"**（门面用**空实现，不返回 null**）。

**四个新发现（21 号文档，2026-10-10）**：
1. **`traceId` 只有"接收"没有"产生"** —— `AiCallContext` 从 header/MDC 读，但**没有组件生成它**
   → 上游不给就永远 null → 台账 `trace_id` 列全空，"和日志串起来"完全落空。
   修法：入口没有就生成，**并写进 MDC**（写进去之后日志框架自动带链路 id，一行 log 代码都不用改）
   + logback pattern 加 `[%X{traceId}]`。
2. **token ≠ 钱 —— 底座缺一张单价表**。`ai_token_usage` 有 token 但算不出成本（缺"哪个模型多少钱"）。
   最小实现：`ai_model_price`（model_name / input_price / output_price / **effective_from, effective_to**）。
   **时间维度不是洁癖**：模型调价后历史账单不能跟着变，账单是要对账的。
   聚合 SQL 必须 `AND u.success = 1` —— **依赖台账的 `success` 字段**，没它连查询都写不出来。
3. **配置分层** —— **方案已定（22 号文档），待实施**。
   现状量化：`assistant-demo/application.yml` **64 行里只有 2 行是服务私有的**
   （port + name），**62 行 97% 会被原样复制**。
   **方案：模块自带 defaults + 服务侧 `spring.config.import`** ——
   `ai-common-defaults.yml` / `ai-memory-defaults.yml` 放各自模块 jar 内，
   服务侧只留 `port + name + import 两行`（**新建服务 = 复制 8 行改 2 个值**）。
   判据一行：**"这条配置不同步会导致跨服务行为不一致吗？会，就是平台级。"**
   （`ai.embed.dimensions` 必须共享 —— 向量空间全局不一致则跨服务检索不可用。）
   **三个关键论点**：① **用结构代替约定**（文件位置固化分层）② **不要靠"jar 里放
   application.yml"**（优先级取决于 classpath 扫描顺序、不可控）③ **它是通往 Nacos 的
   同一条路**（`classpath:` → `optional:nacos:` 只改一个字符串）。
   **⚠️ 实施时必须同时把 `ai.mcp` 里的明文 token 改成 `${MCP_GATEWAY_TOKEN}`** ——
   共享配置会被分发到每个服务，明文凭据绝不能进去。
4. **审核和工具是同一类东西**（都是 `AiServices` 装配点）→ 见上面的"通用模式"。

**12 个问题的扫描框架**（保证不遗漏）：谁在调 / 用什么模型 / 说什么 / 记得什么 / 能做什么 /
知道什么 / 花了多少 / 好不好 / 能不能用 / 安不安全 / 怎么配 / 怎么找问题。

**明确不进底座（写下来避免以后重议）**：
- **已由网关负责**：限流、模型降级与切换、凭证托管 —— 共同特征是"需要看到全部调用"，
  只有网关在那个位置；底座是进程内的，看不到别的服务
- **不是技术能力**：提示词内容（难点在"写什么"）、评测基准（"什么算好"是业务判断）、内容策略
  （提示词的边界：**若第二个助手开始复制第一份的提示词片段，再考虑抽，且大概率是配置项不是模块**）

**四个"现在做"的事项与顺序**：依赖边界 → 上下文五维（含 traceId 产生）→ 台账补字段 → 工具闭环
（+ `AssistantWiringTest` 扩三开关）。

### 🚀 langgraph4j 多智能体（2026-10-10 用户确认要做，设计文档已出）

设计文档：`docs/multiagent-design.html`。已过 superpowers 第 1 步（头脑风暴）。

**langgraph4j 的能力边界（决定了一切）**：
- ⚠️ **本机 `.m2` 里已经有 langgraph4j**（core 1.9.2/1.9.3、langchain4j 集成 1.9.2、bom、parent）。
  **不要重复"它没下载"这个错误判断** —— 之前一次 `find` 超时被 SIGTERM 掐断、输出为空，
  我据此误判。**空输出 ≠ 不存在，命令被中断时要重跑。**
- 扩展点只有 `BaseCheckpointSaver` / `StateSerializer` / `Channel.Reducer` / `NodeAction`。
  **没有 `Store`**（Python 版有：checkpointer 管 thread 级、store 管跨 thread 级）
  → **长期记忆在 Java 生态没有现成抽象，这是与 Python 版的真实差距**
- **会话记忆有现成的**：`MemorySaver`/`FileSystemSaver`（都在 core）+
  `MySQLSaver`/`PostgresSaver`/`OracleSaver`/`RedisSaver`（独立 jar，**坐标已确认存在**）
- 🔴 **`langgraph4j-redis-saver` 依赖 Redisson**（项目用 Lettuce）→ 两套客户端并存
- 🔴 **`LC4jStateSerializer` 是必需的**（ChatMessage 序列化），不传存消息会失败
- HITL：`CompileConfig.interruptBefore(String...)`（**编译期配置，无法条件中断**）
  + `GraphInput.resume()` + `GraphInterruptException.of(Throwable)`
- **通道工厂是 `Channels.appender(Supplier<List<T>>)` / `Channels.base(Supplier<T>)`** ——
  **没有 `MessageChannel` 也没有 `Channel.LastValue` 这两个类**（我曾按公开资料写错）
- `GraphDefinition.START` / `.END` 常量在**接口**上，不在 `StateGraph` 上
- 父 pom 的 `langgraph4j.version=1.9.0` 应改成 **1.9.2** ——
  **1.9.2 是唯一"三件套齐全"的版本**（core + langchain4j 集成 + redis-saver；1.9.3 只有 core）
- 🔴 **`invoke(Map, RunnableConfig)` 是 `@Deprecated(forRemoval = true)`**
  （连同 `invoke(Map)`、`stream(Map,...)`、`stream(Map)`、`streamSnapshots(Map,...)` 共 5 个）。
  **必须用 `GraphInput` 重载**：`invoke(GraphInput.args(map), config)` 给新输入 /
  `invoke(GraphInput.resume(map), config)` 续跑。弃用版本的实现本身就是包一层，
  所以是**签名迁移，不是行为变更**。
  ⚠️ 隐藏语义：弃用版写的是 `inputs == null ? GraphInput.resume() : GraphInput.args(inputs)`
  —— **传 null 会被当成「恢复」**，不是"空输入"。
- 另：`AgentState` 的**双参数** `value(String, T)` / `value(String, Supplier<T>)` 已弃用，
  **单参数** `value(String)` → `Optional<T>` 未弃用（`MultiAgentState` 用的正是它）。
  其他已弃用但未用到：`StateGraph.getStateFactory()` / `addSubgraph(...)`、
  `RunnableConfig.graphPath()`、`RedisSaver.clearCheckpointsCache(String)`、`AgentState.mergeWith`

**验证"某方法是否弃用"的可复用做法**：`javap -v ... | grep -B20 "Deprecated: true"`；
**但更该读 sources jar 里的 `@Deprecated` javadoc** —— javap 只说"弃用了"，
javadoc 才给**官方替代方案**（这次的 `GraphInput` 就是这么找到的）。
（另一条：`mvn compile -Dmaven.compiler.showDeprecation=true` 能让编译器直接报出**自己代码里**的弃用行号。）

- ⚠️ **`StateGraph.compile()` 的校验有盲区，值得单独记**：
  `Edge.validate()` **只校验边的起点、不校验终点**（源码：
  `if (!Objects.equals(sourceId(), START) && !nodes.anyMatchById(sourceId())) throw ...`），
  所以 `addEdge("a", "一个不存在的节点")` 在 compile 时**不报错**。
  **但 `interruptBefore` 会校验** —— `CompiledGraph` 构造器里对每个中断点检查节点存在性，
  不存在就抛 `Errors.interruptionNodeNotExist`。
  → 结论：**漏声明节点时，是靠 `interruptBefore` 那条路径在启动时拦住的**；
  没有中断点的图，这类错误会一直潜伏到运行时。

- ⚠️ **`NodeAction` 看不到 HTTP 请求，也读不到 `RunnableConfig`（连 `threadId()` 都没有）**
  → **凡是节点需要用到的请求上下文，都必须在 state 里显式开一个 channel**，
  由 Controller 在初始 input 里写入。`userId` 就是这么进来的；
  **`sessionId` 容易漏** —— 漏了的症状是只能硬编码一个假值，溯源信息随之丢失。

- 🔎 **`Channels.appender(...)` 是【就地追加】，不是拷贝快照**
  （2026-10-12 字节码核实）。`AppenderChannel$ReducerDisallowDuplicate.apply(old, new)`：
  `if (old == null) return new; for (x : new) old.add(x); return old;` ——
  **`oldList` 被原地修改**。
  → 后果：**`MESSAGES` 的值始终是同一个 `ArrayList` 实例**（跨多个 state 对象共享）。
  → 实践含义：**节点里 `state.messages()` 拿到的是"活引用"不是快照**。
  把它传给异步任务（如 `recordAsync`）就有并发修改风险，
  且异常只会打在异步线程的日志里。
  → ✅ **已加固（33 号）**：`WriteMemoryNode` 改为传 `List.copyOf(state.messages())`。

- 🔎 **`applicationTaskExecutor` 的实际并发上限是 8**
  （Spring Boot `TaskExecutionAutoConfiguration` 默认：
  `corePoolSize=8`、`queueCapacity=Integer.MAX_VALUE`、`maxPoolSize=Integer.MAX_VALUE`）。
  ⭐ **队列无限大 → 永远不触发扩容 → 真实并发就是 8**。
  而 `UserMemoryRecorder` 的抽取任务（LLM 调用，几秒/次）用的正是这个池，
  且它是**全应用共用**的 → 高并发时抽取会排队（**不阻塞图，只让记忆延迟变大**）。
  排查"记忆延迟很久"时，第一个该看的就是这里。
- ⚠️ **`interruptBefore` 是编译期配置，无法"条件中断"** —— 所以像
  "只有危险操作才停下等审批"这种需求做不到；所有经过该节点的路径都会停。
  要按 state 决定本次是否中断，得用 **`InterruptibleAction`（⚠️ **带 i**）**。
- 🔴 **拼写陷阱（2026-10-12 javap 核实）**：`org.bsc.langgraph4j.action` 下有**两个**接口 ——
  `InterruptibleAction`（带 i，**真接口**）和 `InterruptableAction`（不带 i，**空壳标记接口**，
  无任何方法，仅 `extends InterruptibleAction`）。**必须用带 i 的那个。**
  真接口签名：
  ```java
  Optional<InterruptionMetadata<State>> interrupt(String nodeId, State state);
  Optional<InterruptionMetadata<State>> interrupt(String nodeId, State state, RunnableConfig config);
  ```
- ⭐ **时序（字节码核实，决定审批是否有效）**：`NodeHooks.applyActionWithHooksHandlingInterruption`
  里 **`interrupt()` 先调、`WrapCalls.apply`（执行 action）后调** ——
  → **返回 `Optional.of(...)` 时节点根本不执行**；返回 `empty()` 才执行。
  → **危险操作在"停下那一刻真的没被执行"，审批才有意义**（顺序反了就是摆设）。
- **挂载方式**：`addNode` **没有**收 `InterruptibleAction` 的重载；靠 `AsyncNodeAction.node_async(NodeAction)`
  内部 `instanceof InterruptibleAction` 检查 → `Proxy.newProxyInstance` 造同时实现
  `AsyncNodeAction` + `InterruptibleAction` 的代理 → **可中断性自动保留**。
  所以节点类只要**同时实现 `NodeAction` + `InterruptibleAction`** 即可，照常 `node_async(node)`。
- `InterruptionMetadata.builder(nodeId, state).reason(String).build()`
- **框架自带 `AgentEx`**（`org.bsc.langgraph4j.agent`）：`CALL_MODEL_NODE` / `ACTION_DISPATCHER_NODE` /
  `APPROVAL_ACTION` + `AgentEx$ApprovalNodeAction implements AsyncNodeActionWithConfig, InterruptibleAction`
  + `ApprovalState{APPROVED, REJECTED}` + `ToolBehaviour`（`name()` / `addToGraph(StateGraph)`）。
  ⚠️ 要求 state 是 `org.bsc.langgraph4j.prebuilt.MessagesState<M>`；且审批是**整体**的
  （只有 APPROVED/REJECTED），**不按工具危险等级分级** → 不满足本项目"分级审批"需求。
  相关：`addNode(String, CompiledGraph)` 支持子图。

#### 🔴 工具执行与审批的矛盾（2026-10-12 javap 核实，决定 multiagent 工具扩展路线）

**核心矛盾**：需求"给 expert 装工具"与"危险工具要人审"在 AiServices 下**不可调和**。
- LangChain4j 的工具调用是「模型决定 → AiServices **当场同步执行**」，跑在 `ExpertNode.apply()` **内部**
- 审批是**跨 HTTP 请求的异步暂停**（`waiting_approval` → 批准 → 第二个请求 resume）
- **同步钩子表达不了跨请求的暂停** → 工具挂进 AiServices 就"来不及审批"

**核实过的 LangChain4j 挂载点（全部不够用）**：

| 挂载点 | 为什么不行 |
|---|---|
| `beforeToolExecution(Consumer<BeforeToolExecution>)` | `BeforeToolExecution` **只有 `request()` / `invocationContext()` 两个读方法** → **只能观察，不能否决** |
| `afterToolExecution(Consumer<ToolExecution>)` | 事后 |
| `tools(Map<ToolSpecification, ToolExecutor>)` | 自定义 executor 能接管执行，但只能"同步执行或抛异常失败"，**不能暂停** |
| `observability.api.event` 全部事件 | 只有 `ToolExecutedEvent`（已执行）/ `ToolCompensatedEvent`（补偿）—— **没有"将执行"事件** |
| Guardrail（`InputGuardrail` / `OutputGuardrail`） | 是**输入/输出文本**级，不是工具级 |

→ **结论：工具不能挂给 AiServices，必须由图的节点执行**；"要不要停"交给
`InterruptibleAction.interrupt()`（它在 apply 之前，是唯一的运行期暂停点）。

**推荐形态（路线 A：提议 → 判断 → 执行）**：
1. `ExpertAgent` 不挂 `.tools(...)`，改为输出**结构化提议**（如 `ToolProposal{tool, args, reason}`）
2. 新增**工具守卫节点**，同时实现 `NodeAction` + `InterruptibleAction`：
   - `interrupt()`：读 `state.proposedTool` 的危险等级 → 高危险返回 `Optional.of(metadata)` 停下；安全返回 `empty()`
   - `apply()`：**只有放行的情况才会走到这里** → 真正执行工具
3. **必须删掉 `interruptBefore(GATE)`** —— 它是编译期无条件中断，会和按等级的判断打架
4. `MultiAgentState` 需加 `proposedTool` / `proposedArgs` / **`approvalDecided`**
   （`approved` 默认 false，**分不清"还没问过"和"被拒绝了"** → `interrupt()` 会重复中断）
5. 每个 expert 的**工具清单不同**（需求 1）→ 建议一个工具注册表 `ToolRegistry`：
   工具名 → {`ToolRisk`, 执行器, 允许的 expert}，权限校验也在这里

**顺带发现的小笔误**：`ExpertAgent` 的 `@SystemMessage` 末尾 `决定""")` 多了两个引号
（text block 里合法、能编译，但会进提示词）。
且 `ExpertNode.plannedAction` 是**构造参数硬编码**（`"deleteTestData(...)"` / `"noop"`），
与真实工具无关 —— 这正是要改造的核心。

- 🔎 **`RunnableConfig.threadId(x)` 里的 `x` 其实是「threadName」，不是存储 key。**
  框架用它查一个 bucket，拿到**内部 UUID** 作为真正的 threadId：
  `threadName → keyNamingStrategy.threadNameKey(x) → bucket.get() → threadId(UUID)`。
  默认 key（`DefaultKeyNamingStrategy`，前缀 `langgraph4j:`）：
  `langgraph4j:thread:{threadId}` / `langgraph4j:thread:name:{threadName}:active` /
  `langgraph4j:checkpoint:{checkpointId}` / `langgraph4j:thread:{threadId}:checkpoints`。
  → **两个图用同样的 threadName 会命中同一个 key、共用 checkpoint 空间**
  （两个图的 state 结构不同 → 反序列化失败或字段错乱）。
- ✅ **`RedisSaver.Builder.keyNamingStrategy(...)` 可配**（不配则用 `DefaultKeyNamingStrategy`）。
  `KeyNamingStrategy` 5 个方法：`threadKey` / `threadNameKey` / `checkpointKey` /
  `checkpointsKey` / `keyPrefix`。
  **多服务共用 Redis 时的正确隔离手段是加 key 前缀，而不是把命名空间塞进 threadId。**
- ⚠️ **`threadId` 不该带 userId** —— state 里已经有 `USER_ID` channel；
  编进 threadId 会让**明文 userId 进 Redis key**（那恰恰是 ai-memory 被迫做 SHA 脱敏的原因）。
  两套 id 的性质本就不同：`memoryId` 是**有语义的复合 key**（要 parse / 校验 / 反推用户），
  `threadId` 是**不透明标识**（只被拼进 key，从不解析）—— 不该无脑统一。

**实施交付单**：`docs/code-handouts/24-多智能体骨架-实施交付单.md`（14 个文件完整代码 + 5 关检查点）

**三个能力的落点**（核心设计）：
| 能力 | 落点 | 与底座 |
|---|---|---|
| 会话记忆 | 整张图（saver） | ❌ **不共享**（两套数据结构） |
| 长期记忆·读写 | 图入口/出口各一个节点 | ✅ 共享（同一个 `ai_user_memory`） |
| 人在回路 | `approvalGate` 前 | 新增 |

**记忆节点只在头尾各一个，不放进每个专家** —— `state` 全图共享，入口召回一次就够。

**已定决策**：① 先做骨架，agent 占位 ② Checkpoint 用 **RedisSaver**
③ 人在回路用**异步两段式**（发起返回 `waiting_approval` → 人批准 → 第二个请求 `resume`）。
选异步的理由：同步会占用线程、受 HTTP 超时限制、重启即丢、多实例下唤醒找不到人。

**两个社区的坑**：① 并行节点的 executor **每次调用都要挂在 `RunnableConfig` 上**，
只在 compile 注册不够，否则并行静默退化串行；② 工具执行要单独成节点
（**但专家用 `AiServices` 写时不适用** —— 工具循环在 AiServices 内部）。

**验收的关键两条**：换 sessionId 同 userId 仍体现偏好（长期记忆真跑通）；
**停在闸门后重启服务仍能 approve 继续**（checkpoint 价值的唯一验证）。

**⭐ 路由用枚举而不是 String（2026-10-10 用户建议，已落地）**

`SupervisorAgent.route()` 返回嵌套枚举 `SupervisorAgent.Routing { EXPERT_A, EXPERT_B, FINISH }`。
三条已核实的依据（**可复用到任何"让模型输出受控值"的场景**）：
- **LangChain4j 原生支持枚举返回**：`DefaultOutputParserFactory.get()` 里
  `if (rawClass.isEnum()) return new EnumOutputParser<>(...)`；另有 `EnumListOutputParser` / `EnumSetOutputParser`
- **`@Description` 会自动进提示词**：`dev.langchain4j.model.output.structured.Description`（retention=**RUNTIME**），
  `EnumOutputParser.formatInstructions()` 输出 `You must answer strictly with one of these enums:`
  + 每个常量名（`name().toUpperCase()`）+ ` - ` + 描述
  → **枚举值的含义写在注解里，提示词自动同步，不会出现"代码改了提示词忘了改"**
- **解析容错**：`equalsIgnoreCase` + 剥 `[方括号]` + 兼容 `{"value":"X"}`；
  匹配不上**抛 `OutputParsingException`**（不是返回 null）→ 调用方必须 try-catch

**它消掉的一个真实隐患**：原来的 `Set.of(...) + raw.contains(candidate)`，
在模型一次提到多个候选时（"可以先看 expertB，需要清理再走 expertA"）会命中
**集合遍历顺序最靠前**的那个 —— 由 `Set.of` 的顺序决定而非语义决定。
枚举让模型只能交一个值，答不出就是显式异常，而不是静默选错。

**⚠️ 静默开关：是否走原生 JSON schema 由 `ChatModel.supportedCapabilities()` 决定。**
`DefaultAiServices.supportsJsonSchema()` 只看它；`OpenAiChatModel` 默认返回**空集**
（只有显式 `.responseFormat("json_schema")` 才补 `RESPONSE_FORMAT_JSON_SCHEMA`）
→ 现在走**提示词路径**，请求体里没有 `response_format`，**网关零风险**。
哪天有人给模型加上那个配置，会静默切到原生 JSON 模式 —— 那时网关必须认得 `response_format`。

**边界：枚举不进 state。** `state.next` 仍是 String —— 因为
`addConditionalEdges(String, AsyncEdgeAction<S>, Map<String,String>)` 的映射表 key 被框架固定为 String，
且 state 要序列化进 Redis。枚举只活在"Agent 输出契约 → SupervisorNode 入参"这一段，
由 `edgeKeyOf()` 的**穷尽 switch**（刻意不写 default）转成 key —— 加枚举值编译器会报错提醒。

### 消息文本工具住 ai-common（`ChatMessageTexts`）—— 已定

`com.best.ai_common.message.ChatMessageTexts`：`ChatMessage` ↔ 纯文本的转换。

- **抽取理由**：原先被 **5 个类各写一遍**（ai-memory 3 处、ai-multiagent 3 处；
  其中 `UserMemoryContentRetriever` 渲的是 `UserMemory`，不算），**细节四处不一致** ——
  角色标签有没有空格 / 是否跳过 System / 异常返回 `null` 还是 `""` / 一处用类名比较角色
- **统一后的三条约定**：① 角色判断一律用 `instanceof`（**不用** `getClass().getSimpleName()`，
  匿名子类或代理会静默 fallthrough）② `textOf` **永不返回 null** ③ 格式统一 `"角色: 文本"`
- **两个 API 的区别**：`render()` 保留全部角色（调度器用）；
  `renderConversation()` 只留 User/Ai（长期记忆抽取用）
- ⚠️ **两处刻意的行为差异**（改动时要说明）：抽取器的渲染格式多一个空格；
  `SupervisorNode` 对 `ToolExecutionResultMessage` 从误标"用户"改成标"工具"

### `ai-graph` 模块 —— 已定案（见交付单 28）

**定位**：langgraph4j 在这个平台里的**适配薄壳**，**不是"图应用框架"**。

**准入判据（必须同时通过）：**
- **P1：它知道具体 state 吗？**（`MultiAgentState` 或任何具体 state）
  知道 → 第二个图的 state 一变就得改 → 该等
- **P2：它定义对外契约吗？**（HTTP 响应格式 / 错误码）
  定义 → 一旦定就难改，且契约该跟着业务走

**归属判据 P3**：放在「最靠近使用者的那个共同上游」。

> P1+P2 决定"够不够格做公共件"，P3 决定"放哪个公共件"。

**进的 5 个类**（全部零 `com.best` 依赖）：
`GraphThreadIds` / `GraphInterrupts` / `checkpoint/CheckpointRedisOptions` /
`checkpoint/NamespacedKeyNamingStrategy` / `checkpoint/GraphCheckpoints`

- **`GraphCheckpoints` 的价值是"漏不了"**：直接 `RedisSaver.builder()` 时
  "配 `keyNamingStrategy`"和"传 `stateSerializer`"**全靠调用方记得**，
  而前者漏了会**静默串号**；收进工厂方法后 `namespace` 是**必填参数** → 没有默认值可漏
- **不抽两段式 Controller 骨架**（P1+P2 都违：读 `state.pendingAction()` + 定义 `{status,...}`）
  → **抽"判断"不抽"格式"**
- 记忆节点 / `SCHEMA` / state 基类 → P1 不过
- **不做 `@AutoConfiguration`** —— 需要参数的东西不适合"引了就生效"

**⭐ 判据的可执行形式**：`grep -r "MultiAgentState" ai-graph/src/ | wc -l` —— **必须是 0**

**边界**：`ai-common` 至今零 langgraph4j 依赖，**这个边界要保持** ——
所以 `ai-graph` 不依赖 `ai-common`；而 `ChatMessageTexts` 进 `ai-common` 而非 `ai-graph`
（P3：`ai-memory` 绝不该依赖 `ai-graph`）。

**⭐ 补充判据（2026-10-12）：能被绕过的判据不是判据。**
有人提议在 `ai-graph` 里定义 `MemoryAwareState` 基类，好让记忆节点通用 —— 那样
`grep MultiAgentState` 命中 0（判据"通过"了），但换个基类名就绕过去了。
**判据的价值在于它无法被合法绕过**；一旦允许"放一部分形状进去"，P1 就是空的。
配套三条否决理由：① `StateGraph<S>` 泛型**不变**，`NodeAction<基类>` 传不进 `NodeAction<子类>`
② 定义基类 = 在 `ai-graph` 里定 state 形状 ③ 记忆逻辑依赖 `MemoryRecallService` /
`UserMemoryRecorder`（都是 ai-memory 的类）→ 建 `ai-graph → ai-memory` 这条红线。

**记忆节点的正确落点：`ai-memory` 的门面 + `ai-multiagent` 的适配器。**
`ai-memory` 加图无关的 `UserMemoryFacade`（建议名）：
`String recallAsText(userId, query)` / `void writeAsync(userId, sessionId, messages)`
—— **不收 `assistantId`**（构造时已定），调用方摸不到 `memoryId` → 手拼 bug 物理不可能。
适配器住 `ai-multiagent`（认识 state 的代码必须住上层，**这不是"没抽干净"**）。
收口收益：memoryId 拼装 / 召回文本模板 / 失败兜底策略各收一处。

**⭐ 分隔符 id 的通用规则（已踩 3 次）**：`SUMMARY_PREFIX` 少冒号 →
`WriteMemoryNode` 的 `memoryId` 又少一个 `::`（`assistantId::userId` + `sessionId` 直连）。
**只要 id 靠手拼字符串就一定会漏** —— 凡是带分隔符的 id，必须有唯一构造点
（`SessionKey` 就是），且那条链路上的代码**不该有机会自己拼**。
⚠️ `recordAsync` 里 `SessionKey.parse` 在 `taskExecutor.execute` **之外**（同步执行），
所以格式错会被调用方的 catch 接住 → **表现为"长期记忆永远写不进去但服务照跑"**。

### `ai-graph` 模块 —— 三次讨论的立场变化（★ 以最新一次为准）

**★ 已落地：交付单 39（用户选"现在直接抽"）**
- **注意**：34/36/37/38 号**从未落地** —— 39 号是"36/38 的设计 + 模块抽取一起做掉"
- `createAgent(Class<E> routingEnum, RouteDecider decider, String fallbackEdgeKey)` —— **3 个参数**
- 工厂改走 `AutoConfiguration.imports`（ai-graph 不被业务模块组件扫描）
- 配置前缀 `ai.multiagent.*` → **`ai.graph.*`**
- 依赖方向：`ai-multiagent → ai-graph → ai-memory → ai-common`

**★ 落地的坑（交付单 40）**：
- ★ **`@ConfigurationProperties` 前缀写错 = 配置全体失效 + 不报错**。
  实测：yml 里 `ai.multiagent.*` 没改成 `ai.graph.*`，而三项默认值**恰好全等于**配的值
  （`assistant-id` 默认取 `spring.application.name` = 配的值；`ttl` 都是 24；阈值都是 HIGH）
  → **完全静默**。
  **防线**：属性类的 key 有改动时，必须做一次"改一个值 → 看日志里的生效值"的验证；
  **启动日志里那行"生效值"不是装饰，不要删。**
- `ai-multiagent` 的 pom **只留了 `ai-graph` 一个依赖** → 缺
  `spring-boot-starter-web` / `lombok` / `mysql-connector-j` / `langgraph4j-core`(+BOM)（BOM 不能少，
  否则声明 core 时没有版本号）。
  `langgraph4j-redis-saver` **不用加回**（RedisSaver 已搬进 ai-graph）。
- **IDE 标红的判读**：`AiGraphAutoConfiguration` 的 `RedisProperties` 标红 ≠ 代码错
  （Maven 编译 SUCCESS 已证）。根因是 **reactor 编译失败 → IDEA 延迟刷新模块依赖**。
  **先修编译错误，再 Reload Maven。** 不要为消红去改 import 或加依赖。

**★ 第三轮（2026-10-12 22:00 后）：用户要"把整套 ReAct 图抽成 `ai-graph`，其他模块直接依赖"
→ 结论【可以抽，签字】，与前两轮相反。** 立场变化的原因：**形状已收敛**
（38 号定案后节点 / 边 / state / 接口都定型），不再是"一天改三次"的阶段。

- ⚠️ **唯一真障碍 = `SupervisorNode` 的两处绑定**：① `private final SupervisorAgent`
  ② 兜底分支 `routing = Routing.FINISH`（把"调度失败就收尾"这个**业务判断**写进了框架节点）。
  解法：ai-graph 定义 `@FunctionalInterface RouteDecider { ExpertRouting decide(List<ChatMessage>); }`，
  `SupervisorNode(RouteDecider, String fallbackEdgeKey)`；业务侧传 lambda（`Routing implements
  ExpertRouting` → 返回子类型兼容，**零反射**）。
- **要求顺序**：**先落地 38 号、跑通，再搬**。搬移是纯 move，何时做都行；但必须先有能跑的
  基线，否则出问题分不清"搬错了"还是"本来没跑通"（一次只动一个变量）。
- ⚠️ **连带作废：28 号"ai-graph 不依赖 ai-memory"** —— 定位已从"纯 langgraph4j 适配件"
  变成"平台级多智能体编排层"，记忆节点必然带进来（代价：使用者被迫拖上 MySQL）。
- 附加收益：抽完 **`ai-multiagent` 变成纯业务模块**，物理边界本身有价值。

---

### ⏸ `ai-graph` 暂缓（2026-10-12 用户决定"算了先不抽"）—— 第一轮

**5 个类的使用者只有 `ai-multiagent` 一个** → 按"数得出来几个使用者"判据**不该抽**。
⭐ 28 号那句 `grep MultiAgentState == 0` 证的是"**抽了不违反 P1**"，**不是"现在就该抽"**。
**触发条件：出现第二个 langgraph4j 图应用时**，且那时**先建模块再让新应用写代码**
（否则会 `import com.best.multiagent.graph.*` → 业务模块依赖业务模块，比不抽更糟）。
28 号的 P1/P2/P3 判据本身仍有效，只是落地时机变了。

**⭐ 同日下午用户又提议"抽一个通用框架（supervisor + 多 expert + ReAct + 记忆 + 审批）"
—— 结论仍是【不抽】，理由三条：**
1. **使用者还是 1 个**（判据未变）
2. **它比 28 号的提议大一个量级**：从"5 个零依赖工具类"变成"整套骨架"，
   而骨架**必然认识具体 state**（`proposedTool` / `approvalDecided` / `memoryContext`）
   → **直接违反 P1**
3. **它推翻 28 号的边界**：带记忆的框架必须依赖 `ai-memory`
   → 任何使用者（**包括不需要记忆的图**）都被迫拖上 `ai-memory` + MySQL

**最实际的代价**：抽出去之后，当天这种"**一天改三次形状**"的迭代会变慢 ——
（那天拓扑从 `expert→approvalGate` 变成 `expert ⇄ toolExecute` 循环；
 字段从 `pendingAction` 变成 `proposedTool`+`proposedBy`+`approvalDecided`；
 审批从"无条件"变成"按风险等级"）改任何行为都要动公共模块，心理成本上升。

**⭐ 本次提炼的判据 —— 框架 vs 模板（不是形式区别，是"改动方向"区别）：**

| | 方向 | 需要几个实例 |
|---|---|---|
| **框架** | 从多个实例往 ↑ 抽 | **≥2** |
| **模板** | 从一个实例往 → 复制 | **1 就够** |

> **只有 1 个实例时，"抽框架"其实是在把"这一个的形状"当成"通用形状"。**

**建议**：做「多智能体图应用接入清单」（模板 + 文档，对应 `10-新助手模块接入清单.md` 的角色），
**不做公共模块**。
**何时真该抽**：第二个图应用**写完之后**，拿两份代码对比 —— 一眼看出哪些是真共享。

### ✅ `createAgent(枚举, 调度器)` 建图最终版 —— 交付单 36（取代 34/35）

**用户要求**："我把 34/35 合成一个最终文档，只要最终版。要求就是一个通用的 createAgent 方法，
能创建现在这样的 graph，**也不要传那么多参数进去，能默认的就直接默认掉**。"

**最终签名：2 个参数。**
```java
graphFactory.createAgent(SupervisorAgent.Routing.class, supervisorAgent)
```

**默认掉的 8 项** —— `ExpertAgent`（工厂自建：`.chatModel()`、**不挂 tools**）/
`McpToolCatalog` / `McpClientRegister` / `MemoryRecallService` / `UserMemoryRecorder`
（以上注入）、`RedisSaver`（工厂按 `spring.data.redis.*` 自建）、
`assistantId`（缺省取 `spring.application.name`）、`approvalMinRisk`（缺省 `HIGH`）。

**形态变化：工厂从"静态工具类"改成 `@Component`（Spring Bean）** ——
这样它才能自己注入那 8 项依赖，把调用方的参数压到 2 个。
`MultiAgentGraphConfig` 因此只剩两个 Bean（`supervisorAgent` + `multiAgentGraph`），
**建图那一行是 `return graphFactory.createAgent(...)`**。

**新增 `MultiAgentProperties`**（`@ConfigurationProperties(prefix="ai.multiagent")`）：
`assistantId` / `approvalMinRisk` / `checkpointTtlHours`。
⭐ **`approvalMinRisk` 刻意声明成 `String` 而不是 `ToolRisk`** ——
枚举类型会走 `valueOf`，写错一个字母就抛异常让服务起不来；
而这里最需要的是 `ToolRisk.parse` 的"**不认识的值按 HIGH**"（即更严格，不是更宽松）。

**`RedisProperties` 直接注入**（Spring Boot 自带）—— 省掉 4 个 `@Value`。

**其余结论不变**：加 expert 改 2 处 / 加图用复制模块（同服务多图不可行）/
13 条陷阱 / 四用例验收 / `调度决定 ≤ 2` 硬指标 / 第 7 节"不做的事"。

### ✅ 专家接口 `Expert`：统一骨架 + 每个专家自己的准则 —— 交付单 38（最终，取代 37）

**用户的选择过程（值得记）**：先选"完整提示词"（37 号），
落地前自己感觉不对 → **"感觉还是之前那个好"** → 改回骨架方案。

⭐ **他是在"想象写第二、第三份提示词"时感觉到重复的** ——
一个专家看不出重复，三个专家重复立刻可见。
**这是"数得出来几个使用者"那条判据的实操形式。**

**方案对比（最终留档）**：

| | **骨架 + 准则（采用）** | 完整提示词（未采用） |
|---|---|---|
| 专家文件里有什么 | 只有准则（4~6 行） | 完整提示词（30+ 行，含重复） |
| **框架契约（"输出要求"）放哪** | **骨架里，一处** | 每个专家各写一遍 → 框架改一次要改 N 份，漏一个就漂移 |
| `role` 定义处 | 枚举一处 | 枚举 + 每份提示词各一处 |
| **`{{tools}}` 漏了会怎样** | **不可能漏**（在注解里） | 静默退化成纯聊天 |
| **需要建图期校验吗** | **不需要** | 必须 |
| 灵活性 | 骨架结构固定 | 完全自由 |

⭐⭐ **最关键的一条**：
> **完整提示词方案之所以需要那个"占位符校验"，是因为它的"自由"
> 把框架的约束外包给了业务文件。一旦外包出去，就只能靠校验补救。
> 骨架方案把约束收回框架内部 —— 于是那整个校验机制都不需要了。**

**最终形态**：
- `expert/Expert.java`：只有 `String rules()`（**返回空字符串允许** = 没有额外准则）
- 三个实现：**只写"这个岗位特别注意的地方"**（4~6 行），不写"你是谁"、不写输出格式
- `ExpertAgent`：**回到 `@SystemMessage` 骨架**，5 个参数
  （`@V("role")` / `@V("rules")` / `@V("tools")` / `@V("memory")` / `@UserMessage`）
- `ExpertNode`：`routing.expert().rules()` 传进去，**不做任何字符串拼接**
- 工厂：**删掉 37 号加的占位符校验**

⭐ **"输出要求"那段（怎么表达工具调用 / 一次提议几个）是框架契约，不是业务知识** ——
放在骨架里，框架改一次改一处。这是拒绝完整提示词方案的第一条理由。

**留档的判据**：什么情况下才该换成"完整提示词"？——
**某个专家需要完全不同的输出结构**（而不只是不同的准则）。
那时说明它**不该套在这个骨架里** → 该考虑"给它单独一条链路"，而不是"把骨架拆掉"。

#### 附：37 号方案的细节（未采用，保留作对比）

**用户当时的原话**："我希望是完整提示词，这样可以针对不同专家定制不同提示词"
（而不是"骨架 + `{{rules}}` 准则"那个方案）。

**形态：`Expert` 接口 + 实现类，由枚举持有**：
```java
public interface Expert { String systemPrompt(); }        // 完整提示词

enum Routing implements ExpertRouting {
    ORDER("订单助手", new OrderExpert()),                  // ★ 枚举持有实现
    FINISH(null, null),                                   // 两个 null = 非专家
    @Override public Expert expert() { return expert; }
}
```
⭐ **为什么不让 expert 变成"实现类集合"**：`SupervisorAgent.route()` **必须返回枚举**
（LangChain4j `EnumOutputParser` 自动生成提示词的唯一途径），而枚举值必须和 expert 一一对应
→ 改成集合会导致**两处声明** → 回到"漏改一处静默失效"。

**⭐ 本单最重要的设计：占位符校验（建图期硬错误）**

`Expert.systemPrompt()` **必须包含 `{{tools}}` 和 `{{memory}}`**，否则工厂**抛
`IllegalArgumentException`**。理由：
- 漏 `{{tools}}` → 模型看不到工具清单 → **永远返回 NONE → 专家退化成纯聊天，全程无报错**
- 漏 `{{memory}}` → 长期记忆注入不进去 → **"不认识用户"，全程无报错**
> **把静默失效变成启动期的硬错误，是这段校验唯一的、也是全部的价值。**

**`ExpertAgent` 的形态变化**：不再用编译期固定的 `@SystemMessage` 模板，
改成 `@SystemMessage("{{systemPrompt}}")` + `@V("systemPrompt")` ——
**整个系统消息是一个占位符**，由 `ExpertNode` 把专家的提示词（已替换好两个占位符）传进来。

**`ExpertNode` 构造从 4 个参数变 3 个**：`new ExpertNode(routing, expertAgent, toolCatalog)`。
⭐ **判据（再次印证）**：原来在拆 `role` + `clientKey` 两个字段，
这一单又要加 `expert()` 的第三个 → **当发现自己在拆同一个对象的多个字段时，就该直接传那个对象**。

**⚠️ 完整提示词的隐形代价**：框架不再保证"提示词里有什么"。
三个实现里那段重复的 `## 输出要求` 就是第一个信号。
**如果哪天开始出现"三段提示词不一致"或"记不清必须写哪几段"** → 换回"骨架 + `{{rules}}`"方案。
**切换成本很低**（`Expert` 加一个 `rules()`、`@SystemMessage` 换成骨架），**不是不可逆决定**。

**加一个 expert 从 2 处变成 3 处**（多"新建一个 Expert 实现类"）——
**多的这一处本来就不该省：提示词是业务知识，值得拥有自己的文件。**

#### 附：34 号留下的两条判断（保留作方法论）

> 34 号已被 36 取代，但下面这两条有独立价值 —— 它们是我当时判断失误的根因，
> 也是以后遇到"要不要抽"时该先问的问题。

**用户的原始方案**（他自己提的）："我只需要写一个枚举类，通过 for 循环创建多个 ExpertNode，
`create_graph(Enum)` 一个方法就能创建整个图流程"。

⭐ **我当时的失误**：按"注解扫描/运行期注册"去理解，所以说"枚举会退化成 String"。
**但他的方案里枚举还在**，只是用它 for 循环建图 → 那三条批评全部不成立。

⭐ **混淆点：拿"抽模块"的代价去否"提取方法"**：

| | 提取方法（`createGraph`） | 抽模块（`ai-graph`） |
|---|---|---|
| 依赖变化 | **零** | 新模块 + 边界 + 版本管理 |
| 影响范围 | 一个类 | 所有使用者 |
| 改错了 | 改回来 | 动公共 API |
| 何时该做 | **现在**（消除已存在的重复） | ≥2 个使用者之后 |

**它消除的是真实重复**：加一个 expert 现在要改 **4 处**
（`Routing` 枚举 / `addNode` / 条件边 `Map` / yml 的 token），
其中 `addNode` 和 `Map` 是**纯接线、漏了不报错** —— 今天 `FINISH` 那个 bug 就属于这一类。

**设计要点**：
- `ExpertRouting` 接口：`edgeKey()`（= 节点名 = 边 key = MCP server key，三者同一个值）+ `expertRole()`
- ⭐ **`expertRole()` 返回 `null` 表示"不是专家"**（如 `FINISH`）—— 循环跳过它建节点，
  但为它建一条"指向收尾"的边。**这样工厂里不用硬编码 `"finish"` 字符串**
- `@Description`（进 supervisor 提示词）与 `expertRole()`（进专家自己的提示词）**是两件事**，不复用
- ⭐ **用户的决定：`recallMemory`/`writeMemory`/`toolExecute` 都【总是建】、不加开关** ——
  这挡住了"配置项膨胀"的第一步

**收益**：加一个 expert 从**改 4 处**变成**改 2 处**（枚举 + yml），节点与边**物理上不可能漏**。

**⭐ 一条判据**：这个工厂变成"有代价的框架"，是从**它被移出 `ai-multiagent`** 那一刻开始的。
**单子第 9 节明确列出它不做的事**：不加开关 / 不泛型化 `route()` 返回类型 /
不移出本模块 / 不抽 `ai-graph`。

### 🔴 「同一个服务里跑两个图」不可行（2026-10-12 核实，硬障碍）

**① 泛型化 `SupervisorAgent` 会让 LangChain4j 失效。**

看起来可以写成 `interface SupervisorAgent<R extends Enum<R> & ExpertRouting> { R route(...); }`，
但 `AiServices.builder()` 传的是 `SupervisorAgent.class` → **泛型擦除后 `route()` 返回裸 `Enum`**
→ `DefaultOutputParserFactory` 虽然会因 `isEnum()` 走 `EnumOutputParser`，
**但它拿不到"有哪几个值"** → 提示词里 `You must answer strictly with one of these enums:` 生成不出来。

→ **结论：每个图必须有自己的 `SupervisorAgent` 接口。**
（这不是缺陷 —— 让两个图共用一个"调度器契约"本来就没意义，路由语义完全不同。）

**② `createGraph` 硬编码了 `MultiAgentState`** —— 新图要额外字段就得连它一起泛型化。
**③ Controller 是单例注入**（`CompiledGraph<MultiAgentState> graph`）—— 一服务两图要另做请求维度区分。

→ **现在一个服务 = 一张图。加图 = 加服务**（复制模块）。详见交付单 35。

**⭐ 交付单 35 的核心价值在"不用改的清单"**：
`ExpertRouting` / `MultiAgentGraphFactory` / `GraphNodes` / `SupervisorNode` / `ExpertNode` /
`ToolExecuteNode` / `RecallMemoryNode` / `WriteMemoryNode` / `GraphThreadIds` /
`NamespacedKeyNamingStrategy` / **`ai-common` / `ai-memory` 一行都不用动**。
→ **新图的代码工作只剩三件事：写枚举、写提示词、填 yml。**

**⚠️ 新图必须唯一的四项配置**：`server.port` / `spring.application.name` /
**`ai.multiagent.assistant-id`**（★ 它是 checkpoint 命名空间 + 长期记忆 `source_assistant` 双重标识，
重复就串号）/ `ai.mcp.servers.*`。

### 🔧 工具执行的架构（2026-10-12 定，勿重新论证）

**核心矛盾**：LangChain4j 的工具调用是「模型决定 → AiServices **当场同步执行**」
（在 `ExpertNode.apply()` 内部）；而审批是**跨 HTTP 请求的异步暂停**。
**同步钩子表达不了跨请求的暂停** → **工具挂进 AiServices 就来不及审批**。

**已 javap 核实：LangChain4j 没有任何"工具执行前否决"的能力**
（`beforeToolExecution` 的 `Consumer` + `BeforeToolExecution` 只有读方法；事件全是事后的
`ToolExecutedEvent`/`ToolCompensatedEvent`；Guardrail 是文本级）。

→ **铁律：ai-multiagent 的工具不挂给 `AiServices`（`.tools(...)`），
也不注入 `McpToolProvider`；工具一律由图节点执行。**
⚠️ `McpToolProvider` 是 ai-common 里的现成 Bean，**`assistant-demo` 在用，保留不动** ——
那边是"普通助手"，模型自动执行工具是它的正确行为。区别只在"谁来执行工具"。

**MCP 网关侧事实**（`github.com/bi8bo-bubblegum/mcp-gateway`，Python）：
- **tool 清单**：MCP `tools/list`（`McpClient.listTools()`），**按 token 过滤**
- **risk**：**`mcp_gateway` 库 `tools` 表**（`effective_name` UNI / `risk` varchar(8) / `enabled` / `available`）
  - ⭐ 网关**刻意不靠协议透传**精确等级，注释原话："由下游**按工具名查 tools 表**获取：
    那里是 risk 的源头，也拿得到管理员改动后的最新值，不受 tools/list 快照时效限制"
  - `annotations` 只给 high 写 `destructiveHint`（给通用客户端的粗信号）
- ⭐ **关联键 = 工具名**：网关 `AppTool` 构造 `name=descriptor.effective_name`
- **执行**：`McpClient.executeTool(ToolExecutionRequest)` → `ToolExecutionResult.resultText()`
- **三级权限**：`token_services`（服务）→ `token_tools`（工具）→ `tokens.allow_high_risk`（风险）
  ⭐ **`allow_high_risk` 不替代下游审批**（网关注释："即便 token 开了……下游仍应走 HITL"）
  → **本项目只认 `tools.risk`，不看 `allow_high_risk`**
- **`ai-multiagent` 的 DataSource 已指向 `mcp_gateway` 库** → `tools` 表**不用跨库**
- ⚠️ `mysql__mysql_query` 描述写 `(READ-ONLY)` 但 risk=high →
  **risk 是管理员维护的静态判断，不能靠工具自述猜**；且不随参数变化（工具级单值）

**设计（已拍板）**：
- **expert ↔ 工具由网关 token 决定** —— 一个 expert 一个 `McpClient`（不同 token），
  「给 expert 装工具」= 一行 yml 配置，**本项目零维护映射**
- **expert 按服务分**：`Routing{ORDER, ANALYSIS, KNOWLEDGE, FINISH}`，
  **常量名对应 `ai.mcp.servers` 的 key**
  ⭐ 必须按服务分：token 按服务授权，若仍按"有没有副作用"分，
  会出现"判为查询→交给 B→但 B 的 token 没这个服务的工具"的死局
- **未知 risk 按 HIGH**（fail-safe）；阈值可配（`approval-min-risk`）
- **分两步**：①工具清单+执行（`interrupt()` 恒空）②只改 `interrupt()` 方法体
  ⭐ 拆的理由：一次做完的话，停不下来时分不清"判断逻辑错"还是"工具没接上"

**第 2 步的两个坑（先记下）**：
1. **`interrupt()` 必须判 `approvalDecided` 而不是 `approved`** ——
   `approved` 默认 false，**分不清"没问过"和"拒绝了"** → 判错会重复中断、图卡死。
   同理 `apply()` 里判"被拒绝"必须写 `approvalDecided && !approved`（两个条件的与），
   只看 `!approved` 会把所有低危工具误判成"被拒绝"。
2. ⭐ **`approvalDecided` 必须被【重置】**（这是正确性的一部分，不是优化）：
   语义是"**当前这个提议**已经被人回答过"，不是"这个人曾经回答过"。
   不重置的话 —— 第一轮批准完 `approvalDecided` 仍是 true，
   第二轮的危险提议会被 `interrupt()` 直接放行 → **危险操作绕过审批执行，且静默**
   （日志只有"执行成功"）。
   重置点两处：`ToolExecuteNode.finish()`（保证**同一轮内**第二次提议仍被拦）
   + `/chat` 初始化（防御性，保证新请求干净）。

**⭐ 29 号的一处修正**：`McpToolCatalog` **直接注入，不要用 `ObjectProvider` 容错** ——
因为 `ai-multiagent` 本来就离不开数据库（长期记忆 `ai_user_memory` + 台账 `ai_token_usage`，
DataSource 已指向 `mcp_gateway` 库），"没数据库的 ai-multiagent"这个场景不存在。
为一个不存在的场景加降级分支，代价是每个节点里到处 null 判断。
（真正需要容错的是 `ai-common` 这个通用底座，已在 `@ConditionalOnBean(DataSource.class)` 处理。）

产物：`docs/code-handouts/29-multiagent工具接入-清单与执行.md`
     `docs/code-handouts/30-multiagent工具接入-按危险等级中断.md`

### ⭐ 一条通用判据：循环的"中途干预"能力，取决于框架看不看得见循环体

> **循环写在节点内（Java `for`）→ 框架只看到"这个节点跑完了"；**
> **循环写在图上（节点 + 边）→ 框架能看到每一步。**
> 凡需要**中途干预**（审批 / 人工介入 / 断点续跑）的逻辑，**循环体必须拆成节点**。

**本项目的直接后果**：`interrupt()` 是**节点级**的（只在节点执行**前**调一次），
所以「在 expert 节点内部 for 循环做 ReAct」与「审批」**互斥** ——
循环 N 次工具调用，框架只问 1 次，**审批最多拦住第一次**。
且 langgraph4j 1.9.2 **没有节点内主动暂停机制**
（全 jar 仅 `InterruptibleAction` / `InterruptionMetadata` / `GraphInterruptException` 三类；
**没有 Python 版 langgraph 那个节点内 `interrupt()` 函数**）。

**当前设计的已知代价**：每调一次工具就回 supervisor 一次 →
N 次工具 = N 次 expert + **N+1 次 supervisor** 模型调用。

**正解（第 3 步）= 子图**：`addNode(String, CompiledGraph)` 已核实存在；
循环体变成节点，审批点落在子图的 `toolExecute` 上，**supervisor 退化为纯跨专家路由**
（这才是"多智能体调度器"该有的职责）。
⏳ 待验证：子图与父图的 state 类型是否必须一致 / 子图 checkpoint 归属 /
`SubCompiledGraphNodeAction` 是否公开 API。

### 🔴 子图方案已被实测否决（2026-10-12 冒烟测试）

> **子图内部的中断【不会】传播到父图 —— 所以"子图 + 按等级审批"不可共存。**

**实测证据**（`/tmp/SubGraphProbe.java`，父图 `entry → child(子图) → after`，
子图内 `gate` 是恒中断的 `InterruptibleAction`）：

```
[父图] entry 执行
  [子图] subStart 执行
  [子图] gate.interrupt() 被调用 → 返回【有值】(要求停下)
★ 父图正常返回: [entry, sub-start]
```

1. 子图的 `interrupt()` **被调用** ✓（子图内部判断生效）
2. `gate.apply()` **没执行** ✓（中断拦住了节点）
3. **父图 `invoke()` 正常返回** —— 既不抛 `GraphInterruptException`，也没执行 `after`，
   而是**静默返回残缺状态**

**后果（最坏形态）**：Controller 的 `GraphInterruptException.of(e).isPresent()` **永不命中**
→ 走到 `respond()` 返回 `{"status":"done"}` → **前端以为完成，实际工具没执行、
审批环节再也不会触发，且无任何报错**。

**附带核实**：`SubCompiledGraphNodeAction` 是 **public record**，构造含
`CompileConfig parentCompileConfig`（父图 config 会传给子图）；
字节码里还比较了父子 saver，**不一致会抛 `IllegalStateException`**；
但**没有任何子图中断的处理逻辑**。

### ✅ 替代方案（2026-10-12 用户已确认并落地 = 交付单 32）

**用户原话**："按你推荐的来吧，我的意图就是**不要让它每次循环工具调用还要走一遍 supervisor**"。

```
supervisor --(ORDER/ANALYSIS/KNOWLEDGE)--> proposeX --> toolExecute
toolExecute --(proposedTool == NONE)-->  supervisor      (收尾)
toolExecute --(否则，按 proposedBy 路由)--> proposeX      (循环)
```
- 全部节点在**父图同一层** → `InterruptibleAction` 天然生效 ✓
- 每步都是 checkpoint 节点 ✓
- **supervisor 只在"派发 + 收尾"介入**（职责变纯）✓
- 三个专家各一个 `proposeX`，`toolExecute` **共用**（靠 `state.proposedBy()` 选 client）
- **验收硬指标**：`调度决定` 日志 ≤ 2 次（改前 ≈ 工具轮数 + 1），**与工具轮数无关**

**关键设计点**：
- **循环判据 = `proposedTool == NONE`**（模型自己说停 —— ReAct 的自然语义），
  **不新增 state 字段**（SCHEMA 不动，不破坏存量 checkpoint）
- ⭐ **循环只转"同一个专家"**（回 `proposedBy`）；跨专家必须先说 NONE 回 supervisor。
  **这是 by design** —— 跨专家切换本来就是调度器的职责，它是唯一知道全局的角色
- `finish()` 拆成 **`finishContinue`**（保留 `proposedTool`，让循环继续）/
  **`finishDone`**（写 `NO_TOOL`，结束循环）。**不清空**才不会让循环转不起来；
  防重复执行由"proposeX 下一轮必然覆盖"保证
- `finishDone` 写 `NO_TOOL` 而非清空成 `""` —— 后者与 SCHEMA 默认值无法区分，排查时看不出"刚结束循环"

⭐ **规则补充（第 4 次"改 A 忘 B"）**：`edgeKey()` 改成 `name().toLowerCase()` 后，
`Routing.FINISH` 返回 `"finish"`，但 Config 里 Map 的 key 还是 `"FINISH"` → **条件边查不到映射**。
→ **凡是把某个字符串的"产出方式"改掉，必须列出这个字符串的【所有消费点】。**

**证据留存**：`docs/probes/SubGraphProbe.java` + `docs/probes/README.md`
（子图方案的否决实证 + 3 个跑一次性 Java 脚本的踩坑记录）

### 跑一次性 Java 冒烟测试的姿势（可复用）

```bash
CP=$(find ~/.m2/repository/org/bsc -path "*1.9.2*" -name "*.jar" ! -name "*sources*" | tr '\n' ':')
CP="$CP<async-generator jar>:<slf4j jars>:<jackson jars>"
java -cp "$CP" /tmp/Probe.java     # Java 11+ 单文件模式，自动编译+运行
```
- ⚠️ **`-path "*1.9.2*"` 不能省**：本机 `.m2` 里有 langgraph4j-core 的 1.8.10/1.9.2/1.9.3，
  不加限定会因 classpath 顺序用到旧版 → 报"找不到符号 `reason(String)`"
- ⚠️ `org.bsc.async` 是**独立 artifact**（`org.bsc.async:async-generator`），
  不在 `org.bsc.langgraph4j` 下，漏了会 `NoClassDefFoundError: AsyncGenerator$Cancellable`
- ⚠️ `mvn -pl <单模块> dependency:build-classpath` 会因兄弟模块未安装而失败

### 结构备注

- **主架构文档**：`docs/ai-platform-architecture.html`（单页 HTML + 内嵌 SVG，
  双击即可看）。内容：平台分层图 / 底座能力池 / **三形态 × 能力取用矩阵** / 三个静默失效开关。
  **迭代时只改这一份，不要另起**。
- **多智能体设计文档**：`docs/multiagent-design.html`（图拓扑 + 异步两段式时序）。
- 两模块包组织原则不同：`ai-common` 按**技术角色**、`ai-memory` 按**业务能力**。暂不统一
- **改包名有三处字符串引用不会自动改**：`@MapperScan` 注解值、mapper XML 的
  `namespace` / `typeHandler` / `type`、`resources/mapper/` 下镜像包名的目录。
  改完必须跑**真实查询**，编译期照不出来

## 六、本机编译（每次验证都要用）

沙箱 shell 里 **`mvn` 和 `java` 都不在 PATH 上**，必须用绝对路径 + 显式 JAVA_HOME：

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests compile
```

- JDK 17 在 `/opt/homebrew/Cellar/openjdk@17/17.0.20.1/`（另有 openjdk 26 / 21 / 11）
- `-o` 离线模式可用（依赖已缓存）
- 父 pom 的 `<module>` 已统一为小写（`ai-common` / `ai-memory` / `assistant-demo`）✓
- 目录名一律小写 `ai-common` / `ai-memory`（Maven 惯例），**交付单里的路径也按小写写**

