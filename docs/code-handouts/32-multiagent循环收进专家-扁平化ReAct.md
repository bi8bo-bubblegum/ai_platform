# 32 · 循环收进专家：扁平化 ReAct

> **前置**：31 号的所有必修项已完成（编译 `BUILD SUCCESS`、`finish()` 四条路径全部收口）。
>
> **本步目标**：**让"每次工具调用都要回一遍 supervisor"消失。**
>
> **你原话**："我的意图就是不要让它每次循环工具调用还要走一遍 supervisor" —— 这就是验收标准。
>
> ⚠️ **本单放弃了子图方案**，原因见 30 号与附注：实测证明**子图内部的中断不会传播到父图**
> （父图会静默返回残缺状态，Controller 检测不到"需要审批"）。
> 本单用的是"条件边实现循环"，全部节点在父图同一层。

---

## 0. 先修一个连带 bug（31 号遗留）

`SupervisorAgent.edgeKey()` 改成 `name().toLowerCase()` 之后，`Routing.FINISH` 返回的是
**`"finish"`（全小写）**，而 `MultiAgentGraphConfig` 里那个 Map 的 key 还是 **`"FINISH"`**：

```java
// MultiAgentGraphConfig 第 99 行，现在（错）：
                                "FINISH", GraphNodes.WRITE_MEMORY
```

**后果**：`SupervisorNode` 往 state 写 `next = "finish"`，条件边拿它去 Map 里查，
只有 `"FINISH"` → **查不到** → 图在"该收尾"的时候路由失败。

**修法**（改成向枚举要，和另外三个 key 保持同一来源）：

```java
                                SupervisorAgent.Routing.FINISH.edgeKey(), GraphNodes.WRITE_MEMORY
```

> **为什么会漏**：31 号我只给了 `edgeKey()` 的实现，没有连带指出
> "Config 里那个 `"FINISH"` 字面量也要跟着改"。**又是一次"改 A 忘了 B"** ——
> 这次是按我们说的新规矩该被列出来的：**凡是把一个字符串的产出方式改掉，
> 必须列出这个字符串的【所有消费点】。**

---

## 1. 本单范围

| # | 动作 | 文件 |
|---|---|---|
| 0 | 修 `"FINISH"` → `Routing.FINISH.edgeKey()` | `MultiAgentGraphConfig` |
| 1 | **`toolExecute` 的出边改成条件边**（本步核心） | `MultiAgentGraphConfig` |
| 2 | `finish()` 拆成 `finishContinue()` / `finishDone()` | `ToolExecuteNode` |
| 3 | 提示词加一句"可以分多轮调用工具" | `ExpertAgent` |
| 4 | 删掉未使用的 `ObjectProvider` import（31 号遗留） | `MultiAgentGraphConfig` |

**节点数量不变**（还是 7 个），**state SCHEMA 不变**，**ai-common 不动**。
改动比前一单小得多 —— 因为需要的基础设施都已经在了。

---

## 2. 循环是怎么成立的

**核心就一件事：`toolExecute` 执行完之后，"去哪"不是在编译期决定的，而是在运行期读 state 决定的。**

```
                       ┌──────────────────────────────┐
                       │  读 state.proposedTool()      │
                       │    == NONE ? → 回 supervisor  │
                       │    否则      → 回 proposeX    │
                       └──────────────────────────────┘
```

一轮完整的过程（假设模型要连调两个工具）：

```
supervisor            → 派发给 analysis                  【调度决定 1 次】
analysis(propose)     → 提议 mysql__mysql_query #1
toolExecute           → 真执行 #1，结果写进 messages
   └─ 条件边读到 proposedTool 还是工具名 → 回 analysis
analysis(propose)     → 看到 #1 的结果，提议 #2
toolExecute           → 真执行 #2，结果写进 messages
   └─ 条件边读到 proposedTool 还是工具名 → 回 analysis
analysis(propose)     → 信息够了，提议 NONE
toolExecute           → 看到 NONE，什么都不做
   └─ 条件边读到 NONE → 回 supervisor
supervisor            → FINISH → writeMemory → END        【调度决定 2 次】
```

**全程 `supervisor` 只出现 2 次**（派发一次、收尾一次），无论中间转了几轮工具。
这就是你要的效果。

### 2.1 为什么判据选 `proposedTool`，而不是"轮次上限"

- **它是模型自己说的**。ReAct 的本质就是"模型决定何时停止思考和行动" ——
  用轮次上限是把模型的自主权换成一个拍脑袋的数字。
- **它已经是现成的**。`proposedTool = NONE` 本来就表示"不需要工具"，
  我们只是**让这个信号第一次有了第二个用途**（原来的用途是让 `toolExecute` 跳过执行）。
- **不需要新增 state 字段**。SCHEMA 一点不用动 —— 这对你已经跑起来的 checkpoint 是好事。

### 2.2 一个必须理解的新约束：循环只转"同一个专家"

条件边回的是 `state.proposedBy()` —— **提出这个建议的那个专家**。
所以：

- **同一专家内的多轮工具调用** → 在循环里转，不打扰 supervisor ✓
- **需要换专家**（比如订单查完要去做数据分析）→ 模型必须说 `NONE` 结束循环，
  回到 supervisor，再由它派发给另一位 ✓

这是**对的**，不是妥协：**跨专家切换本来就应该由调度器决定**，
它是唯一知道"全局有哪几个专家、各自能干什么"的角色。
让 `toolExecute` 自己跳到另一个专家，等于把调度权分叉成了两份。

---

## 3. 代码

### 3.1 `MultiAgentGraphConfig`（改造）

**改动 A**：加一个常量 + 一个路由方法（放在类末尾，和 `node(...)` 并列）

```java
    /**
     * 工具执行完之后"结束循环、回调度器"的边 key。
     *
     * <p>它只是条件边 Map 里的一个 key，不是节点名 —— 所以刻意<b>不</b>放进 {@code GraphNodes}，
     * 也不复用 {@code Routing.FINISH.edgeKey()}。
     * 理由：{@code FINISH} 的含义是"整个任务结束"，而这里是"本轮 ReAct 结束、回去看全局" ——
     * 两件事借用同一个名字，是将来最容易读错的地方。
     */
    private static final String LOOP_END = "loopEnd";

    /**
     * 工具执行完之后该去哪。
     *
     * <p><b>这是整个扁平化 ReAct 的心脏</b>：它把"循环"从 Java 的 for 语句
     * 挪到了图上的一条条件边 —— 于是每一步都是真实的图节点，
     * <b>既能被 checkpoint，也能被 {@code InterruptibleAction} 拦住</b>。
     *
     * <p>不用 for 循环写的原因见 30 号：{@code interrupt()} 是节点级的，
     * 节点内部的循环它看不见，审批最多只能拦住第一次。
     *
     * @return {@code LOOP_END} 表示结束循环回调度器；否则返回专家名（= 回那个专家的 propose 节点）
     */
    private static String routeAfterTool(MultiAgentState state) {
        String tool = state.proposedTool();

        // 模型说"不需要再调工具了" —— 本轮 ReAct 结束
        if (tool == null || tool.isBlank() || "NONE".equals(tool)) {
            return LOOP_END;
        }

        // 还有工具要调 → 回到【提出这个建议的那个专家】
        String by = state.proposedBy();
        if (by == null || by.isBlank()) {
            // 边界：提议非空但来源缺失（正常流程不会出现）。
            // 这时候不能瞎猜一个专家 —— 回调度器是唯一安全的选择：
            // 最坏结果是"少循环一轮、由 supervisor 重新派发"，而不是"跑到一个错的专家那里执行"
            log.warn("proposedTool={} 非空但 proposedBy 为空，无法确定回哪个专家，改为回调度器", tool);
            return LOOP_END;
        }
        return by;
    }
```

**改动 B**：`toolExecute` 的出边（替换原来那一行 `.addEdge`）

```java
                .addEdge(AGENT_ORDER, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_ANALYSIS, GraphNodes.TOOL_EXECUTE)
                .addEdge(AGENT_KNOWLEDGE, GraphNodes.TOOL_EXECUTE)
                // ★ 原来是 .addEdge(GraphNodes.TOOL_EXECUTE, GraphNodes.SUPERVISOR)
                //   一条固定边 —— 意味着每执行完一个工具都必须回调度器。
                //   现在改成条件边：还要工具就回原专家，不需要了才回调度器。
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE,
                        AsyncEdgeAction.edge_async(MultiAgentGraphConfig::routeAfterTool),
                        Map.of(
                                SupervisorAgent.Routing.ORDER.edgeKey(), AGENT_ORDER,
                                SupervisorAgent.Routing.ANALYSIS.edgeKey(), AGENT_ANALYSIS,
                                SupervisorAgent.Routing.KNOWLEDGE.edgeKey(), AGENT_KNOWLEDGE,
                                LOOP_END, GraphNodes.SUPERVISOR
                        ))
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);
```

**改动 C**：顺手删掉未使用的 import（31 号遗留）

```java
- import org.springframework.beans.factory.ObjectProvider;
```

**改动 D**：上一节说的 `"FINISH"` 修正

```java
                .addConditionalEdges(GraphNodes.SUPERVISOR,
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        Map.of(
                                AGENT_ORDER, AGENT_ORDER,
                                AGENT_ANALYSIS, AGENT_ANALYSIS,
                                AGENT_KNOWLEDGE, AGENT_KNOWLEDGE,
                                // ★ 原来是 "FINISH"（大写），和 edgeKey() 返回的 "finish" 对不上
                                SupervisorAgent.Routing.FINISH.edgeKey(), GraphNodes.WRITE_MEMORY
                        ))
```

> 改完之后 `SupervisorAgent` 要 import 进来 —— 如果已经 import 了就不用动。

### 3.2 `ToolExecuteNode`（改造：`finish()` 拆成两个）

**为什么必须拆。** 原来 `finish()` 无条件清空 `proposedTool`；
而现在条件边**要靠 `proposedTool` 判断循环是否继续** —— 一清空，循环就永远转不起来
（每次都判成"结束"，直接回 supervisor，等于什么都没改）。

但也不是所有路径都该保留它：

| 出口 | 循环应该 | 所以用 |
|---|---|---|
| 工具执行成功 | **继续**（让模型看结果再决定） | `finishContinue` |
| 工具执行异常 | **继续**（让模型看到失败原因，换个方式） | `finishContinue` |
| 被人工拒绝 | **结束**（拒绝是终止信号） | `finishDone` |
| MCP 客户端找不到 | **结束**（这是配置错误，再转也没用） | `finishDone` |
| 没有提议（NONE） | 不动 state（`proposedTool` 本来就是 NONE） | `Map.of()` |

```java
    /**
     * 【循环继续】收尾：把提议<b>原样留着</b>，让条件边读到它、回到原专家。
     *
     * <p>为什么留着不会导致"同一个工具被执行两次"：
     * 回到 propose 节点后，模型一定会<b>重写</b> {@code proposedTool}（要么换个工具，要么写 NONE），
     * 所以这个"旧值"在下一轮执行前必然被覆盖。它只活到"条件边读完"为止。
     */
    private Map<String, Object> finishContinue(AiMessage message) {
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    /**
     * 【循环结束】收尾：把提议置成 {@code NONE}，条件边读到它会路由回调度器。
     *
     * <p>为什么在这里显式写 NONE 而不是清空成 {@code ""}：
     * 两者对条件边来说都是"结束"，但 {@code ""} 和 SCHEMA 的默认值无法区分 ——
     * 排查问题时看到 {@code ""} 你分不清"从没提议过"还是"刚结束循环"。
     * 写 NONE 语义明确。
     */
    private Map<String, Object> finishDone(AiMessage message) {
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.PROPOSED_TOOL, NO_TOOL,
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }
```

对应的四处调用改成：

```java
        // ① 被拒绝 → 结束循环
        if (state.approvalDecided() && !state.approved()) {
            log.info("工具 {} 被人工拒绝", tool);
            return finishDone(AiMessage.from("[操作被拒绝] 已取消：" + tool
                    + "。如果用户另有需要，可以改用别的方式帮他。"));
        }

        // ② 找不到客户端 → 结束循环
        if (client == null) {
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", state.proposedBy(), tool);
            return finishDone(AiMessage.from("[工具 " + tool + " 未能执行] 未配置 "
                    + state.proposedBy() + " 的 MCP 通道"));
        }

        try {
            ...
            // ③ 执行成功 → 继续循环（让模型看到结果再决定）
            return finishContinue(AiMessage.from("[" + (failed ? "工具 " + tool + " 返回错误"
                    : "工具 " + tool + " 结果") + "]\n" + text));
        } catch (Exception e) {
            ...
            // ④ 执行异常 → 继续循环（让模型看到失败原因）
            return finishContinue(AiMessage.from("[工具 " + tool + " 执行失败] " + e.getMessage()));
        }
```

### 3.3 `ExpertAgent`（提示词加一段）

```java
            关于工具：
            - 需要调用工具时，在 tool 字段填工具名、arguments 字段填参数对象
            - 不需要调用工具时（或已经有足够信息回答），tool 填 NONE、arguments 填空对象
            - 一次只提议一个工具；看到它的返回结果后，再决定是否需要下一个
            - 只能从上面的工具清单里选，不要编造工具名
            - 不要假装已经执行了 —— 真正的执行由后续环节完成
```

> 第三行是新增的。它把"循环"这件事明确告诉模型 ——
> **模型不知道有这个循环，它只会"一次给出全部动作"**，那样你永远看不到多轮 ReAct。

---

## 4. 验收

**这一单的验收标准就是你的原话：supervisor 不该被每轮工具调用叫醒。**

### 4.1 编译 + 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

### 4.2 数日志 —— 这是本单唯一的硬指标

```bash
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"先看看数据库里有哪些表，然后统计一下订单表有多少行","sessionId":"loop-1"}' \
  | tee /dev/stderr
```

然后数两个日志出现的次数：

```bash
grep -c "调度决定" <日志文件>
grep -c "提议工具" <日志文件>
```

**期望的结果：**

| 日志 | 期望次数 | 含义 |
|---|---|---|
| `调度决定` | **≤ 2** | 派发 1 次 + 收尾 1 次。**中间转几轮都不该增加** |
| `提议工具` | **≥ 2** | 说明循环真的转了（一次提议 + 一次决定是否继续） |

**这两个数字的关系就是本单的全部价值**：

- 改之前：`调度决定` 次数 ≈ 工具轮数 + 1（每轮都回 supervisor）
- 改之后：`调度决定` 恒为 2，**与工具轮数无关**

> 如果模型偷懒只提议一次就说 NONE，`提议工具` 只有 2 次、`调度决定` 还是 2 次 —— 这也算通过。
> **`调度决定 ≤ 2` 是硬指标，`提议工具` 的次数取决于模型。**

### 4.3 回归：原来的四项必须还能过

```bash
# 低危工具：直接执行，不停
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'
# 期待 {"status":"done"}

# 高危工具：必须停下来
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'
# 期待 {"status":"waiting_approval","proposedTool":"mysql__mysql_query",...}
```

**特别要验的是"批准之后"** —— 因为现在批准走的是 `finishContinue`（不清提议），
必须确认它不会导致同一个工具被重复执行：

```bash
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
# 然后数日志里 mysql__mysql_query 被执行的次数 —— 必须恰好 1 次
grep -c "工具执行成功: tool=mysql__mysql_query" <日志文件>
```

> ⚠️ 如果这里 >1，说明循环没被正确终止（`proposeX` 没有覆盖 `proposedTool`）。
> **这是本单最需要盯的一个副作用。**

### 4.4 跨专家：必须经过调度器

```bash
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345，再分析一下它的金额是否异常","sessionId":"cross-1"}'
```

一个合理的执行是：订单专家查完 → 说 NONE → 回调度器 → 调度器派给数据分析专家 → 分析完 → 收尾。
**`调度决定` 这时应该是 3 次**（派发、换专家、收尾）—— 跨专家就是该多一次。

**如果 `调度决定` 只有 2 次，说明专家把两件事一个循环里做完了**（也可能，取决于模型）。
两种都不是错 —— 你要验的是**循环内部不会冒出额外的 `调度决定`**。

---

## 5. 自查清单

**编译期**
- [ ] `"FINISH"` 字面量在 `MultiAgentGraphConfig` 里已经不存在了（`grep -n '"FINISH"'` → 应为空）
- [ ] `ObjectProvider` 的 import 已删
- [ ] `routeAfterTool` 的返回值和条件边 Map 的 key 集合**完全一致**
      （`AGENT_ORDER`/`AGENT_ANALYSIS`/`AGENT_KNOWLEDGE`/`LOOP_END`）——
      少一个就是运行期路由失败

**行为**
- [ ] `调度决定` ≤ 2（本单的硬指标）
- [ ] 批准后工具**只执行一次**（4.3 那条）
- [ ] 高危工具仍然会停下（审批没被循环破坏）
- [ ] 拒绝后循环结束、不重复提议（走 `finishDone`）

**边界**
- [ ] 一个纯问答请求（"你好"）→ `提议工具: NONE`，`调度决定` 2 次，无工具执行

---

## 6. 本单之后的能力盘点

| 能力 | 状态 |
|---|---|
| 按工具危险等级审批 | ✅ 30 号 |
| 低危直接执行 | ✅ 30 号 |
| **同一专家多轮工具调用不打扰调度器** | ✅ **本单** |
| 跨专家切换必经调度器 | ✅ 本单（by design） |
| 每一步都能 checkpoint / 断点续跑 | ✅ 一直在（因为循环体是节点） |

**还没做的**（不着急，等你哪天真需要再说）：

1. **并行工具调用** —— 模型一次提议多个工具。现在是"一次一个"，
   要改的话 `ExpertProposal.tool` 变成 `List<String>`，
   条件边不用动，但 `toolExecute` 要处理多个请求（`McpClient` 支持并发）。
2. **循环轮数上限** —— 现在只靠 `recursionLimit(25)` 兜底。
   如果发现模型会无限转，加一个 `toolRounds` channel + 条件边里判断即可。
3. **工具结果过长** —— 大结果会全部进 `messages`，把下一轮的上下文撑大。
   要治的话在 `ToolExecuteNode` 里做截断（`abbreviate` 已经有个简易版，但只用在日志上）。
