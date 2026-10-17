# 项目长期记忆 · ai_platform

## 一、交付方式（强制）
- 代码不直接改项目源码（唯一例外：用户明确说"你来写"）→ 输出到 `docs/code-handouts/` 的 `.md`：完整路径 + 完整代码 + 逐行讲为什么 + 检查点 + 自查清单
- **两阶段**：① 只在对话里对齐方案（取舍/判据/清单），他点头后才出 .md。例外：他明确说"只要代码"；复核/诊断/定位类（只读给结论、无待实施新代码）
- 完整替换已有文件时，文首必须列「相对上一版新增了哪几行」；"替换/删除/移动"类指令必须同时给出「操作完成后的完整状态」

## 二、写作与表达
禁空话套话；能用具体事件就不用定性形容词，能用数字就不用形容词；先对齐意图再动手。

## 三、给方案的纪律
- 自己写组件前必须先回答「为什么不用现成的」
- **抽象要等形状，契约要趁没数据**。辅助判据：数得出来几个使用者——真被重复写 N 遍才是"消除已存在的重复"
- "调研 → 设计 → 编码"是三段，资料齐全 ≠ 方案已定

## 四、架构边界（已定，勿重新论证）
- 上游已有 ai-gateway（OpenAI 兼容）；**AI-Common 只做「配置 → LangChain4j 实例」装配**，不做路由/限流/凭证托管，零基础设施依赖
- token 计量就在 SDK 层（ChatModelListener）：流式 usage 可能拿不到、拿不到 TTFT、只覆盖 Java 侧；ModelUsage 必须有 estimated 布尔（准确值与估算值不混字段）
- 模块划分：AI-Common（模型+可观测）← AI-Memory（记忆）← assistant-* / ai-orchestrator；记忆属执行编排故独立成模块
- **AiServices 装配写在业务模块自己的 config**，AI-Memory 只导出能力 Bean
- 流式返回 = TokenStream（Spring MVC + SseEmitter，无 WebFlux）
- 持久层统一 MyBatis-Plus：实体必须 class+@Data（record 不行）；`@TableName(autoResultMap=true)` 不能省；可选功能自动装配三件套（漏 `after=DataSourceAutoConfiguration` 会静默不装载）
- 两个记忆：会话记忆（Redis TTL 滑动，助手×用户×会话）/ 长期记忆（MySQL 事实表，用户级跨助手共享）。memoryId = `assistantId::userId::sessionId`（只解析前两个 ::）；Redis key = 前缀+assistantId+sha256(userId)+sessionId（userId 脱敏）
- 向量检索不用 EmbeddingStore（SQL where 先过滤，候选集恒定≤500；触发切换条件：单用户>2000 条/跨用户检索/上了向量库）；一期不做 UPDATE/DELETE，相似度≥0.95 去重丢弃

## 五、ai-orchestrator 模块（2026-10-14 定案）
- `ai-graph` / `ai-multiagent` **已弃用**（multiagent 只是验收台，隔离/并行/终止三核心一项没验证）
- 正式模块 = `ai-orchestrator`（框架）+ `ai-orchestrator-demo`（业务+验收台），框架零 import demo；43 号已写入项目（用户授权例外）
- 契约冻结：RunOutcome / RunState 序列化 / AgentRole / TaskSpec / TaskOutcome / ApprovalGate / RunStateStore；实现不冻结
- 工具**不挂** AiServices（挂了会先执行后审批，beforeToolExecution 只能看不能否决）；链路：提议→工具名校验→查 risk→ApprovalGate→McpClientRegister 执行
- v1 范围 12 项：编排者-工作者/上下文隔离/显式 scope/结构化交接/循环迭代/组合式终止/预算兜底/错误显式标记/人在回路/可暂停可恢复（内存+Redis）/会话历史（三层上下文，指代消解放 Planner）/可观测四事件
- 上图判据：需要「同一份状态走不同拓扑」时才上图；所有请求形状相同 → 是循环不是图（勿再争）

## 六、已知状态与工具链
- **项目不是 git 仓库**，改动无回退点
- 已修：启动 NPE（ModelRegister profile 空校验+人话报错）、Jackson 三断点（Lombok 化、is-getter @JsonIgnore、Redis 专用 mapper）、turn/synthesize 缺 @UserMessage
- 遗留：`ModelRegister.DefaultProfile()` 大写 D 违反命名惯例（未改）
- 编译：`cd /Users/best/project/ai_platform && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && /opt/homebrew/bin/mvn -o -DskipTests compile`
- 沙箱坑：SERVER__PORT/SERVER__HOST 覆盖 yml；HTTP_PROXY 拦 curl（验 HTTP 用 JVM 内探针）；本机 BSD grep 不支持 `\|`（grep 返回空 ≠ 不存在）；交付单代码块用脚本逐字节拼装比对
- 交付单进度 01–44（39/40 作废），登记在 docs/code-handouts/README.md

## 七、AgentScope Java（2026-10-16 起调研）
- 用户方向：**新建一个模块**探讨学习 AgentScope Java；**主要走 SDK 开发，Harness 功能足够**，Service（预览版）不碰
- 已通读 v2 中文文档：harness 全部 11 页 + building-blocks + going-to-production/release-notes/change-log/faq
- **模型层已定（2026-10-16 三次修订后）**：不用 DashScope；**不用 agentscope starter**——改为**自建模型注册表**（对齐 ai-common 的 `ModelRegister` 形态）：yml `ai.models.<name>.{base-url,api-key,model-name}` → `ModelProperties`(@ConfigurationProperties(prefix="ai")+字段 models) → `ModelRegistrar` 构造 `OpenAIChatModel` 并 `ModelRegistry.register` → agent 里 `.model("fast")` 按名引用。形态 = **Spring Boot**（MVC），Builder 提 `@Bean`，REST 端点 + curl 验收；版本 **2.0.4**（BOM 管），实测编译+启动通过
- **配置类形态（2026-10-16 用户拍板，勿再改）**：`record ModelProperties(Map<String, ModelSpec> models)` —— **record + Map**，`@ConfigurationProperties(prefix="ai")`（绑定路径 = prefix + 字段名(`models`) + key = `ai.models.<name>.*`）；三个生成参数默认值用 **`@DefaultValue` 标在 record 构造参数上**（实测生效：不写→0.2，写→覆盖）；必填校验放 `ModelRegistrar.require(...)`。`@DefaultValue` **不影响元数据条数**（仍 1 条、照样标黄）——运行时默认值与 IDE 元数据是两件事。
  **判据（重要）**：绑定/运行 与 IDE 静态提示是两件事——record + Map 绑定完全正常（实测 `[fast, strong]`），只是 IDE 不给 `ai.models.<name>.*` 补全且标黄（Spring 元数据处理器不递归 Map 值类型，只生成 1 条）。**用户不要 JavaBean（噪声大）**；要消除标黄就加可选的 `additional-spring-configuration-metadata.json`（纯 IDE 体验，加模型需同步）
- **注册表形态实测事实**：① Map 型 properties 绑定路径 = prefix+字段名+key，写错会**静默空 map**；② `.model(String)`/`.fallbackModel(String)` **立即解析**（builder 配置时，非 build() 时）→ 注册必须先于 builder 配置，Spring 里用构造参数注入 registrar 保序；③ `ModelRegistry` 无枚举接口，名册要自己留；④ **模型上烤进的 generateOptions 在调用方传空 options 时生效、显式传值则按字段覆盖**（假端点截获请求体实测）→ 生成参数默认值写在 `ModelSpec` 字段（0.2/2048/false）、yml 只写覆盖项，**不要**再建 GenerateOptions bean（两个来源会打架）；⑤ `env -u AI_API_KEY` 在本机无效（shell 已有该变量，另有注入源）→ 验「缺密钥」用命令行显式覆盖
- `OpenAIChatModel.builder()` 实测：`modelName` 构建期强制（全裸 build 抛 NPE）；**builder 路线不读环境变量**（env 全空也照发空 key 吃 401）；不给 baseUrl 默认打 `api.openai.com/v1`
- 文档坑：`OpenAIChatModel.Builder.defaultOptions(...)` 在 2.0.4 不存在（实际是 `generateOptions`）；`AgentEventType` 实际 31 个常量（含 `AGENT_RESULT`/`HINT_BLOCK`）；`ModelCallStartEvent` 无 `getModelName`；Spring `ServerSentEvent.Builder` 无 `name()`
- 文档坑：OpenAI 兼容端点上「工具 + 结构化输出」可能冲突，需 `nativeStructuredOutputWithTools(false)`
- 关键事实：JDK17+（匹配）；`HarnessAgent.builder()` 启动时配一次、每请求 `build()`+close；模型用 `provider:model` 字符串经 ModelRegistry 解析
- 学习计划：16 章（每章吃透一个 harness 功能）+ 毕业项目（多 agent 全功能组合），模块 `ai-agentscope`，交付单 45 号起；**等用户确认章节粒度/模块名/出稿节奏后才生成文件**
- **日志规范（用户要求）**：应用组件一律 Lombok `@Slf4j`（不用 `System.out`，`{}` 占位符）；模块 pom 需加 `org.projectlombok:lombok`（optional）。**探针类（独立 main / exec:java）保留 sout**——命令行工具输出，不是应用日志
- **工具系统（第 4 章实测）**：`Tool` 是注解接口；`ExecutionConfig` 无并发度（并发看 `@Tool(concurrencySafe)`）；**参数校验读 `ToolUseBlock.content`（原始 JSON）不是 `input` map**；正常返回 `state=RUNNING` 非 SUCCESS；异常被框架接住转 `Error: Tool execution failed:` 文本
- **权限系统（第 5 章实测）**：`PermissionBehavior` 四值（含 PASSTHROUGH）；五模式矩阵 DEFAULT全ASK / ACCEPT_EDITS只读ALLOW / EXPLORE非只读DENY / BYPASS全ALLOW / **DONT_ASK全DENY（不是免问）**；链路 deny→ask→工具自身→模式兜底，**allow 规则不参与判定**（是 HITL 产物）；**规则 bucket key 必须是 toolName，`ruleContent` 只能填 null**（`ToolBase.matchRule` 实现就是 `ruleContent == null`）
- **进度（2026-10-16）**：46 总纲 / 47 第 1 章 / 48 第 2 章 / 49 第 3 章 均已出并登记 README；**三次修订后形态 = Spring Boot + 模型注册表**（去 starter），全套代码在 /tmp/ascope-boot 编译+启动实测通过；50/51（第 4 章工具 + 第 5 章权限 HITL）已出；下一批 52–53（第 6 章上下文 + 第 7 章记忆）
