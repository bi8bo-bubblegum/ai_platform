# 41 · 多智能体架构调研与诊断（重新规划前的对齐材料）

> **本文档只做两件事：记录调研事实、给出对现状的诊断。**
> **不含任何"待实施代码"** —— 重新规划的具体方案等这份材料里的问题裁定之后再出。
>
> **调研时间**：2026-10-14 · **调研范围**：7 份原始文献（含两篇互相点名反驳的）

---

## 一、调研出处

| # | 来源 | 性质 |
|---|---|---|
| 1 | Anthropic《How we built our multi-agent research system》(2025-06-13) | 生产系统的正面经验 |
| 2 | Cognition (Devin)《Don't Build Multi-Agents》(2025-06-12) | **明确反对**多智能体的论战文章 |
| 3 | Anthropic《Building effective agents》(2024-12-19) | 模式总纲（workflow vs agent） |
| 4 | OpenAI Agents SDK · Agent orchestration | 两种编排模式的官方分野 |
| 5 | LangGraph 官方 multi-agent 文档 | supervisor / swarm / network / handoffs |
| 6 | 《Loop Engineering：多智能体协作架构内幕与最佳实践》(2026-10-12) | 最系统的工程化最佳实践 + 反模式清单 |
| 7 | Spring AI Alibaba 官方文档 | **Java 生态的 Graph 引擎（现成的）** |

> 1 和 2 发布于**相差一天**，且 2 在正文里直接点名批评 OpenAI Swarm 和 AutoGen 这类"多智能体"路线。

---

## 二、最硬的三条事实（都带原话/数字）

### 2.1 成本：多智能体比单 agent 贵约 **3.75 倍**

Anthropic 原文给出的两个数字：

> "agents typically use about **4× more tokens than chat** interactions, and multi-agent systems use about **15× more tokens than chats**"

→ 推导：多智能体 ÷ 单 agent ≈ 15 ÷ 4 ≈ **3.75 倍**。（这个倍数是我算的，原文只有那两个数）

并且明确了**适合什么**：

> "multi-agent systems excel at valuable tasks that involve **heavy parallelization**, information that **exceeds single context windows**, and interfacing with numerous complex tools"
>
> "excel especially for **breadth-first queries** that involve pursuing **multiple independent directions** simultaneously"

**不适合什么**（这条最要紧）：

> "some domains that **require all agents to share the same context** or involve **many dependencies between agents** are **not a good fit** for multi-agent systems today. For instance, **most coding tasks** involve fewer truly parallelizable tasks than research"

### 2.2 分歧的核心：**子 agent 之间能不能看见彼此**

Cognition 提出两条原则，直接把"orchestrator + 并行 worker"这条路否掉了：

> **Principle 1** — Share context, and share **full agent traces**, not just individual messages
>
> **Principle 2** — **Actions carry implicit decisions**, and conflicting decisions carry bad results

它给的反例（Flappy Bird）：子 agent 1 把"绿管子背景"理解成了超级马里奥风格，子 agent 2 做的鸟不像游戏素材 —— **两个子 agent 看不见对方在做什么**，各自基于冲突的假设行动，最后由上层去缝合两处误解。

> "**Subagent 1 and subagent 2 cannot see what the other was doing** and so their work ends up being **inconsistent with each other**."
>
> "I would argue that Principles 1 & 2 are so critical... that you should **by default rule out any agent architectures that don't abide by them**."

Cognition 的推荐：**单线程线性 agent + 一个"上下文压缩"模型**（这甚至值得微调一个小模型）。

### 2.3 调和的答案：**两种多智能体不是一回事**

Anthropic 那套能成立，是因为它的子任务**天然独立**（"研究 A 国市场"和"研究 B 国市场"互不依赖，谁先谁后都不影响结论）。
Cognition 那套反例失败，是因为子任务**必须一致**（背景和鸟必须画风统一）。

**所以判据不是"要不要多智能体"，而是：**

> **子任务之间，需不需要"看见对方做了什么"才能做对？**
>
> - **不需要** → 并行是对的（Anthropic 模式）→ 但只在"广度优先、独立方向、上下文装不下"时才值得那 3.75 倍
> - **需要** → 并行就是 Cognition 那个 Flappy Bird（画风不一致）→ 必须串行 + 共享完整轨迹

---

## 三、模式清单（Anthropic 的五档 + Loop Engineering 的四种拓扑）

**Anthropic 的 workflow 五式**（这些都是 **workflow**，不是 agent）：

| 模式 | 一句话 | 判据 |
|---|---|---|
| Prompt chaining | 拆固定步骤，上一步喂下一步，中间可插检查点 | 任务能干净地拆成固定子任务 |
| **Routing** | 分类输入 → 交给专门的后续任务 | 类别清晰、分类可做准 |
| Parallelization | 分段（独立子任务并行）/ 投票（同一任务跑多次） | 子任务可并行，或需要多视角 |
| Orchestrator-workers | 中央 LLM **动态**拆解 → 派 worker → 综合 | **无法预测需要哪些子任务** |
| Evaluator-optimizer | 一个生成、一个评审，循环 | 有明确评价标准且迭代有增益 |

**Anthropic 的总纲**（这句话是所有讨论的锚点）：

> "we recommend finding the **simplest solution possible**, and only **increasing complexity when needed**. This might mean **not building agentic systems at all**."

**Loop Engineering 的四种拓扑**：流水线 / 主管-下属 / 辩论-评审 / 共享黑板。
它的取舍轴是「**隔离性 ↔ 涌现性**」：越隔离越可控越慢，越开放越能涌现越难收敛。

**OpenAI Agents SDK 的两种编排**：

| | Manager pattern（agents as tools） | Handoffs |
|---|---|---|
| 控制权 | manager 一直持有 | specialist 接管本轮 |
| 结果归一 | 在 manager 处**一处**强制 guardrails | 专家直接面向用户 |
| 适合 | 要单一答案 + 组合多专家 | 路由本身就是工作流 |

---

## 四、Loop Engineering 给的五个构件（这是自查清单）

一套多智能体系统要在五个地方都成立，缺一个就塌：

| 构件 | 关键要求 |
|---|---|
| **任务分解** | 每个子任务必须有**显式 scope**：输入定义、产出格式、成功判据、边界。不能只丢一句"你去研究一下 X" |
| **交接** | **先浓缩再上交**。不是回传原始上下文 —— 读 40 篇网页，回传的是"3 个结论 + 引用位置"。建议强制附带**置信度**和**未覆盖的子问题** |
| **循环迭代** | 每轮要有明确"本轮目标"和"本轮增量"，避免无谓重算 |
| **终止条件** | **组合式**：子任务全完成 / 多轮增量低于阈值 / 预算耗尽。**永远给预算兜底** |
| **错误传播** | 错误要能被**标记**，不能**静默合并**。三层：子 agent 自检 + 主管交叉验证 + 全局回退 |

**反模式清单（原文"需要警惕的"）：**

- 简单问题起一堆子 Agent，把并行变成纯开销
- **主管既是规划者又亲自干执行** → 主干成为瓶颈和单点故障
- 没有终止条件，或只靠"我觉得完了"
- **子 Agent 把原始上下文原样回传** → 主管窗口迅速溢出
- 角色边界模糊，一个 Agent 又写代码又审查又发消息
- 只在最终输出做评估 → 跑偏后无法定位是哪层的问题

**可观测性四类事件**（原文建议至少记这些）：任务派发（task_id / scope / 目标 agent）、任务完成（耗时 / token / 置信度 / 回传摘要）、循环收敛（本轮 delta / 终止原因）、异常（失败 / 超时 / 超限）。

---

## 五、★ 诊断：现在的 `ai-graph` + `ai-multiagent` 有什么问题

**先说好话**：机制层做得比多数人扎实 —— `InterruptibleAction` 的用法、`approvalDecided` 的重置、扁平化 ReAct、记忆的异步抽取、checkpoint 命名空间隔离，这些都是对的，而且踩过的坑都有记录。

**问题出在"选型"，不在"实现"。**

### 5.1 核心问题：**这是 workflow 被当成了 agent 来做**

`Routing` 枚举是 `ORDER` / `ANALYSIS` / `KNOWLEDGE` —— 这是**按领域分类**：

```java
@Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")        ORDER,
@Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")   ANALYSIS,
@Description("知识库相关：检索文档、查资料、找已有方案或说明")            KNOWLEDGE,
```

**这是 Anthropic 五式里的第 2 式 `Routing`（workflow），不是 `Orchestrator-workers`（agent）。**

两者看着像，判据在这一句（Anthropic 原文对 orchestrator-workers 的界定）：

> "the key difference from parallelization is its **flexibility** — subtasks **aren't pre-defined**, but **determined by the orchestrator based on the specific input**"

现在的子任务**是预定义的**（三个枚举值写死在编译期）。**分类边界固定 → 那就是 routing。**

**代价是什么**：为了一个 routing，付了图引擎的全部成本 —— 状态机、checkpoint、ReAct 循环、中断恢复、`recursionLimit` 兜底、跨请求的审批握手。**而这些机制没有一个是因为"要路由"才需要的。**

### 5.2 三个 expert **没有上下文隔离** —— 这是最实质的技术问题

现在三个 expert 都读同一个 `MultiAgentState.messages`，都往同一个列表里写。

对照 Cognition 的 Principle 1：**这不符合"共享完整轨迹"** —— 它只共享了消息列表，而每个 expert 的推理过程（它看了哪些工具结果、怎么得出结论的）并没有被结构化地传递；同时对照 Anthropic 的隔离收益：**没有任何隔离**。

**合起来就是**：

> **拿到了多智能体的全部复杂度成本，没有拿到任何一项多智能体收益。**
>
> - 注意力隔离？没有（都看同一份上下文）
> - 错误局部化？没有（一个 expert 的错结论直接进共享 messages，下游照单全收）
> - 并行？没有（严格串行，一次一个 expert）
> - 上下文超窗？没有解决（所有历史都堆在同一个列表里，只是被 `max-messages` 截断）

### 5.3 五个构件里，缺三个

| 构件 | 现状 |
|---|---|
| 任务分解 | ⚠️ 有（supervisor 派发），但**子任务没有显式 scope / 成功判据** |
| 交接 | ❌ **没有浓缩协议** —— expert 的 `reply` 原样进 messages，没有置信度、没有"未覆盖的子问题" |
| 循环迭代 | ⚠️ 有（ReAct 循环），但**没有"本轮增量"概念** |
| 终止条件 | ⚠️ **只有 `NONE` + `recursionLimit` 兜底** —— 缺增量阈值、缺 token 预算 |
| 错误传播 | ❌ **完全缺失** —— `ExpertNode` 的 catch 只写了一句话进 messages，**错误被静默合并** |

第 5 条对照原文：*"关键是错误要能被'标记'而不是'静默合并'"*。

### 5.4 可观测性只有一半

项目里有**成本类**事件（token 台账 ✓）。缺的是**行为类**四件事：任务派发 / 任务完成（含耗时、置信度）/ 循环收敛（含本轮 delta 与终止原因）/ 异常。现在的日志有"调度决定"和"工具执行成功"，**没有 task_id、没有 scope、没有收敛原因** —— 出了问题只能顺着日志猜。

### 5.5 一个结构性问题：**主管既规划又执行**

`SupervisorNode` 既决定派给谁，也是唯一决定"任务结束"的人；而 `writeMemory` / `recallMemory` 又挂在同一张图上。对照反模式清单第 2 条："主管既是规划者又亲自干执行 → 主干成为瓶颈和单点故障"。

严格说现在是"主管 + 单点执行（toolExecute 共享）"，还没到"主管亲自干活"，但**共享 `toolExecute` 意味着所有 expert 的执行语义被绑死**（同一个审批阈值、同一套中断逻辑，无法按 expert 差异化）。

### 5.6 最根本的一条：**还没有回答"这个多智能体应用要解决什么任务"**

`ORDER`/`ANALYSIS`/`KNOWLEDGE` 更像是**一个企业助手的三个能力面**，而不是**一个需要多智能体的复杂任务**。

对照 Anthropic 的适用条件（并行 + 超窗 + 多工具）—— 一个"查订单/查数据/查文档"的助手**一条都不满足**：子任务独立但不需要并行（用户一次只问一件事），上下文也不会超窗。

**所以最可能的结论是：这个场景本来应该是第 1 档（routing workflow）+ 第 2 档的审批中断，而不需要第 3 档。**

---

## 六、一个必须回答的问题：**为什么不用现成的**（项目纪律）

调研里第 7 条是这次最意外的发现：

> **Spring AI Alibaba Graph** 已经是一个 Java 原生的 Graph 引擎，官方描述包含：
> - 工作流编排（LLM、工具作为节点）
> - **多 agent 协作**
> - **人为介入**（流程中设中断，等人类确认或修改状态值）
> - **并行分支、嵌套子 graph**
> - 与 Spring Boot / Spring AI 无缝集成
> - 还有 Studio 可视化调试 + 低代码导出 Java 源码

也就是说：**`ai-graph` 现在自研的每一项能力（图、状态、中断、人在回路、子图），在 Java 生态里都有一个官方实现。**

按项目纪律，"自己写某个组件之前，必须先在交付单里回答『为什么不用现成的』"。现在要做的是把这个回答补上 —— 而它有三种可能的结果：

1. **换掉**：迁移到 Spring AI Alibaba Graph，`ai-graph` 退化为适配层
2. **不换，但要写清理由**：比如已深度绑定 LangChain4j（Alibaba 那套基于 Spring AI，模型/工具抽象不同），迁移成本大于收益
3. **不换，而且缩小范围**：`ai-graph` 只保留第 2 档（单 agent 循环），第 3 档不做

---

## 七、待裁定的问题（答完这些才能出方案）

| # | 问题 | 为什么它决定方案 |
|---|---|---|
| **1** | **`ai-multiagent` 到底要解决什么任务？** 是一个"企业助手"（查订单/查数据/查文档），还是真有一个"需要拆解成多个独立子任务并行做"的复杂任务？ | 决定它是第 1 档（routing）还是第 3 档（orchestrator）。**这一条不定，后面全是空谈** |
| **2** | 三个 expert **需不需要看见彼此做了什么**？ | Cognition 判据。需要 → 必须串行共享轨迹；不需要 → 并行才成立 |
| **3** | 自研 `ai-graph` vs **Spring AI Alibaba Graph** vs 缩小范围只做第 2 档？ | 决定"要不要继续写图引擎" |
| **4** | "三档能力"这个分层你认可吗？还是你希望仍然只有一个统一入口？ | 决定模块怎么切 |
| **5** | 第 5.3 节缺的三个构件（交接浓缩协议 / 终止条件 / 错误标记）**现在补还是等有真实任务再补**？ | 前两个现在补成本低（还没存量数据），第三个要按真实任务设计 |
| **6** | 审批中断机制（现有做对的部分）**保留为独立能力**，从图里拿出来，是否认可？ | 它是真需求，不该跟着 routing 一起被否掉 |

---

## 八、一句话总结

> **现在的 `ai-graph` 是一台造得很好的机器，装在了一个不需要它的工序上。**
>
> 图的机制（checkpoint / 中断 / 恢复）是对的，而且确实有真需求（审批）；
> **但它们服务的是"审批 + 记忆"，不是"多智能体"** ——
> 而"多智能体"这一层，目前是三段写死在枚举里的提示词路由，
> 既没拿到隔离，也没拿到并行，只拿到了复杂度和 token 成本。
