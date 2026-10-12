# 27 · graph 通用件盘点：什么该抽、什么必须等

> **触发**：用户问"有没有哪些东西可以抽出来，作为后续每一个 graph 图都能复用的"
>
> **现状**：`ai-multiagent` 13 个源文件 / 654 行。用户已经建了 `graph/` 包
> （`GraphThreadIds` 18 行 + `NamespacedKeyNamingStrategy` 40 行），**但两个类都还没接线**。

---

## 0. 判据：分两类，结论恰好相反

沿用交付单 26 的判断框架，但换成更适合"抽什么"的问法：

| 问题类型 | 判据 | 结论 |
|---|---|---|
| **抽抽象**（抽接口 / 基类 / 工具类） | **形状是否已从真实用例里长出来** | 使用者只有一个、且差异点还没暴露 → **等** |
| **建契约 / 定模块边界** | **可逆性** —— 改它成本是否随时间上升 | 一旦有存量数据或对外承诺 → **趁早** |

**再加一条很实用的辅助判据**（这次盘点靠它一次定出了最该做的那项）：

> **同一段逻辑如果真的被重复写了 N 遍，那它不是"提前抽象"，而是"消除已存在的重复"。**
> 判据从"我猜将来会有第二个使用者"变成"现在已经数得出来几个".

---

## 1. 盘点结果

| # | 候选 | 现在几个使用者 | 依赖图的形状吗 | 裁定 |
|---|---|---|---|---|
| **1** | **`ChatMessage` ↔ 文本**（`render`/`roleOf`/`textOf`/`lastUserText`） | **5 个类 / 8 个方法，跨 2 个模块** | ❌ 只依赖 `ChatMessage` | ✅ **现在抽** → `ai-common` |
| **2** | **中断判断**（`GraphInterruptException.of` + `getState().nextNodeId()`） | 1（`AgentController`，但每个图都要） | ❌ **零 state 依赖** | ✅ **现在抽**（很小） |
| 3 | `GraphThreadIds` / `NamespacedKeyNamingStrategy` | 1（已建，**未接线**） | ❌ | ⏳ **先接线**，跑通后再提模块 |
| 4 | `RedisSaver` 装配（saver + serializer + ttl + keyNaming） | 1 | ⚠️ 需 `AgentStateFactory` | ⏳ 可抽（泛型方法），优先级低 |
| 5 | 两段式响应格式 `{status, threadId, pendingAction}` | 1 | ✅ **依赖 state 的 `pendingAction`** + 是**对外 API 契约** | ❌ **等** |
| 6 | 记忆节点 `RecallMemoryNode` / `WriteMemoryNode` | 1 | ✅ 依赖 `state.userId()` / `messages()` | ❌ **等第二个图** |
| 7 | `SCHEMA`（`Channels.base/appender` 的定义） | 1 | ✅ 就是 state 本身 | ❌ 等 |
| 8 | `SupervisorNode` / `ExpertNode` / `ApprovalGateNode` | 1 | ✅ 强业务 | ❌ 不抽 |

**第 5、6 条要特别说明**：它们**看起来**最像"每个图都要"，但恰恰是**最不该现在抽的** ——
因为抽它们必须先定义"图的 state 长什么样"。而 `MultiAgentState` 是**这一个图**的 state，
第二个图的 channel 集合现在只能猜。**这是典型的"形状未知"，必须等。**

---

## 2. 最该做的一项：`ChatMessageTexts`（5 个类 / 8 个方法）

### 2.1 先看重复有多真

| 位置 | 方法 | 角色标签 | 过滤 System？ | 异常时返回 |
|---|---|---|---|---|
| `ai-memory/session/summarizer/LlmConversationSummarizer` | `render` + `roleOf` + `textOf` | 用户 / 助手 / **系统** / 其他 | 否 | `""` |
| `ai-memory/user/LlmUserMemoryExtractor` | `render` + `textOf` | 用户 / 助手（**无空格**） | **是**（跳过 System） | **`null`** ⚠️ |
| `ai-multiagent/node/SupervisorNode` | `render` + `roleOf` + `textOf` | 用户 / 助手 / 系统 | 否 | `""` |
| `ai-multiagent/node/RecallMemoryNode` | `lastUserText` | — | 只要最后一条 User | `""` |
| `ai-multiagent/node/ExpertNode` | `lastUserText` | — | 同上 | `""` |

（`ai-memory/user/UserMemoryContentRetriever.render` **不参与** —— 它渲染的是 `UserMemory`，不是 `ChatMessage`。）

### 2.2 顺手暴露出的两个隐患

**🔴 `SupervisorNode.roleOf` 用类名做字符串比较 —— 脆弱：**

```java
private String roleOf(ChatMessage m){
    String n = m.getClass().getSimpleName();
    return switch (n){
        case "SystemMessage" -> "系统";
        case "AiMessage" -> "助手";
        default -> "用户";          // ← ToolExecutionResultMessage 会被判成"用户"
    };
}
```

只要 langchain4j 用匿名子类或代理包一层，`getSimpleName()` 就不再等于 `"AiMessage"`
→ 静默 fallthrough 成"用户"。**而 `LlmConversationSummarizer.roleOf` 用的是 `instanceof`（正确写法）。**
两份实现，一份对一份脆 —— 这种不对称本身就是信号。

**🟡 `LlmUserMemoryExtractor.textOf` 在异常时返回 `null`：**

```java
} catch (Exception e){ log.warn(...); }
return null;        // ← 调用方必须判 null，漏判就是 NPE
```

它的 `render` 里恰好判了（`text == null || text.isBlank()`）所以没炸 —— **但这靠的是"记得判"**。
统一成 `""` 之后，这类隐患从根上消失。

### 2.3 完整实现（新建 `ai-common/.../message/ChatMessageTexts.java`）

```java
package com.best.ai_common.message;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * {@link ChatMessage} 与纯文本之间的转换。
 *
 * <p><b>为什么它该住在 ai-common。</b> 这套转换原先被<b>5 个类</b>各写了一遍
 * （{@code ai-memory} 三个、{@code ai-multiagent} 三个，其中两个只渲染 UserMemory 不参与），
 * 而且细节<b>四处不一致</b>：角色标签有没有空格、要不要跳过 System、
 * 异常时返回 {@code null} 还是 {@code ""}、以及一处用类名字符串判断角色。
 * 抽取它<b>不是"提前抽象"</b> —— 是在消除已经数得出来的重复。
 *
 * <p><b>为什么放 ai-common 而不是 ai-graph。</b> 使用者横跨 {@code ai-memory}
 * 和 {@code ai-multiagent}，共同上游是 {@code ai-common}；而这里只依赖
 * {@code dev.langchain4j.data.message.*}，不给 ai-common 引入任何新依赖。
 *
 * <p><b>三条统一后的约定：</b>
 * <ol>
 *   <li>角色判断一律用 {@code instanceof}，不用类名比较</li>
 *   <li>{@link #textOf} <b>永不返回 null</b> —— 拿不到就给空串，调用方不用判</li>
 *   <li>渲染格式统一成 {@code "角色: 文本"}（冒号后一个空格）</li>
 * </ol>
 */
public final class ChatMessageTexts {

    private ChatMessageTexts() {}

    // ------------------------------------------------------------ 单条消息

    /**
     * 角色的中文标签。
     *
     * <p>用 {@code instanceof} 而不是 {@code getClass().getSimpleName()} ——
     * 后者一旦遇到匿名子类或代理就会静默 fallthrough 到兜底分支。
     */
    public static String roleOf(ChatMessage message) {
        if (message == null) {
            return "其他";
        }
        if (message instanceof UserMessage) {
            return "用户";
        }
        if (message instanceof AiMessage) {
            return "助手";
        }
        if (message instanceof SystemMessage) {
            return "系统";
        }
        if (message instanceof ToolExecutionResultMessage) {
            return "工具";
        }
        return "其他";
    }

    /**
     * 取消息的纯文本。<b>永不返回 null</b>。
     *
     * <p>为什么必须 try-catch：{@code UserMessage.singleText()} 在"图文混合"的消息上
     * 会抛 {@code IllegalArgumentException}。这不是错误，跳过即可 ——
     * 但它意味着<b>光判 null 是不够的</b>。
     */
    public static String textOf(ChatMessage message) {
        if (message == null) {
            return "";
        }
        try {
            if (message instanceof UserMessage um) {
                return orEmpty(um.singleText());
            }
            if (message instanceof AiMessage am) {
                return orEmpty(am.text());
            }
            if (message instanceof SystemMessage sm) {
                return orEmpty(sm.text());
            }
            if (message instanceof ToolExecutionResultMessage tm) {
                return orEmpty(tm.text());
            }
        } catch (Exception e) {
            // 拿不到文本不是错误 —— 多模态消息、或只有工具调用的轮次都可能走到这里
            return "";
        }
        return "";
    }

    // ------------------------------------------------------------ 消息列表

    /**
     * 把整个消息列表渲染成文本，<b>所有角色都保留</b>。
     *
     * <p>适用于"把对话当成一段上下文"的场景，比如给调度器看。
     */
    public static String render(List<ChatMessage> messages) {
        return render(messages, false);
    }

    /**
     * 只渲染"对话双方"（用户 + 助手），跳过系统提示和工具结果。
     *
     * <p>适用于"抽取用户说了什么"的场景 —— 比如长期记忆抽取，
     * 那里系统提示和工具输出都是噪声。
     */
    public static String renderConversation(List<ChatMessage> messages) {
        return render(messages, true);
    }

    private static String render(List<ChatMessage> messages, boolean conversationOnly) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (conversationOnly
                    && !(message instanceof UserMessage)
                    && !(message instanceof AiMessage)) {
                continue;
            }
            String text = textOf(message);
            if (text.isBlank()) {
                continue;
            }
            sb.append(roleOf(message)).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    /**
     * 最后一条用户消息的文本（按时间倒序找）。找不到就返回空串。
     *
     * <p>这个方法是 {@code RecallMemoryNode} 和 {@code ExpertNode} 里各写了一遍那个
     * {@code lastUserText} 的合并版 —— 它们逐字符相同。
     */
    public static String lastUserText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return textOf(um);
            }
        }
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

### 2.4 5 个调用点怎么改

| 文件 | 动作 |
|---|---|
| `ai-memory/.../LlmConversationSummarizer` | **删** `render` / `roleOf` / `textOf` 三个私有静态方法；调用处 `render(msgs)` → `ChatMessageTexts.render(msgs)` |
| `ai-memory/.../LlmUserMemoryExtractor` | **删** `render` / `textOf`；调用处 `render(turns)` → `ChatMessageTexts.renderConversation(turns)` |
| `ai-multiagent/.../SupervisorNode` | **删** `render` / `roleOf` / `textOf`；调用处 → `ChatMessageTexts.render(...)`（同时修掉字符串比较角色那个隐患） |
| `ai-multiagent/.../RecallMemoryNode` | **删** `lastUserText`；调用处 → `ChatMessageTexts.lastUserText(...)` |
| `ai-multiagent/.../ExpertNode` | **删** `lastUserText`；调用处 → `ChatMessageTexts.lastUserText(...)` |

**两个要留意的行为差异（都是刻意的、且影响极小的变化）：**

1. **`LlmUserMemoryExtractor` 渲染格式多一个空格**（`用户:x` → `用户: x`）。
   这是统一格式的必然结果，对模型输入无实质影响。
2. **`SupervisorNode.roleOf` 的兜底从"用户"变成"其他"**。
   原先 `ToolExecutionResultMessage` 会被误标成"用户"，现在标"工具"。
   **这是修正，不是回归。**
3. `LlmUserMemoryExtractor` 原先过滤 System 的行为**被保留**（走 `renderConversation`）；
   `SupervisorNode` / `LlmConversationSummarizer` 保留 System 的行为也**被保留**（走 `render`）。

> 按项目习惯，这三条应当在 commit 信息或交付记录里写清楚 —— 它们是"看起来等价、其实有细微差别"的改动。

### 2.5 检查点

```bash
mvn -o -pl ai-common -am -DskipTests clean install
mvn -o -DskipTests clean compile
mvn -o -pl ai-memory test
```

**真正的验收不是编译通过，是三个行为不回归**：摘要在工作、事实抽取在工作、调度器能正常判断出下一个节点。
跑一次 `curl` 对话（走到审批闸门）就能覆盖后两个。

---

## 3. 第二项：中断判断（也很小，但值得抽）

`AgentController` 里有三段"和这个图无关"的通用逻辑：

```java
GraphInterruptException.of(e).isPresent()          // 判断是不是中断
graph.getState(config).nextNodeId()                // 查"停在哪个节点"
snapshot.state().pendingAction()                   // ← 这个依赖 state，不抽
```

**前两个零 state 依赖**，可以抽成：

```java
public final class GraphInterrupts {

    /** 这次异常是不是"图停在中断点"（而不是真的出错） */
    public static boolean isInterrupt(Throwable e) {
        return GraphInterruptException.of(e).isPresent();
    }

    /**
     * 下一个要执行的节点名。停在中断点时为闸门名，正常跑完为空串。
     */
    public static String nextNodeId(CompiledGraph<?> graph, RunnableConfig config) {
        StateSnapshot<?> snapshot = graph.getState(config);
        if (snapshot == null) {
            return "";
        }
        String next = snapshot.nextNodeId();
        return next == null ? "" : next;
    }

    /** 是不是正停在指定的闸门节点上 */
    public static boolean waitingAt(CompiledGraph<?> graph, RunnableConfig config, String gateNode) {
        return gateNode.equals(nextNodeId(graph, config));
    }
}
```

**注意第三个方法 `waitingAt` 才是关键收益** —— 它把"中断点判断"这件事从
"每次都要记得 `getState()` + `nextNodeId()` + 判 null"变成一次调用。
`getState()` 那行漏判 null 会 NPE，而且只在特定时序下触发。

**⚠️ 但响应格式（`{status, threadId, pendingAction}`）不要抽** ——
它依赖 state 的 `pendingAction` 字段，而且它是对外 API 契约，一旦定就很难改。
**抽"判断"，不抽"格式"。**

---

## 4. 第三项：`ai-graph` 模块 —— 建议**先接线，再搬**

`graph/` 包里的两个类（`GraphThreadIds` / `NamespacedKeyNamingStrategy`）**形态已经完全确定**
（不依赖任何图的形状），所以它们**有资格**成为公共模块。

但**顺序建议反一下**：

```
现在：类已建 ✓ → 未接线 ✗
建议：先接线 → 跑通一次"发起 → 审批 → 恢复" → 再决定搬到哪个模块
```

**理由**：现在搬出去，等于**搬运两个还没被执行过的类**。而"接线"这件事本身就是必须做的
（不接线它们不生效）—— 顺手就能验证它们真的能工作。跑通之后搬，风险更低，
而且那时候你会更清楚 `ai-graph` 该包含什么。

**如果现在就建 `ai-graph`，依赖长这样：**

```
ai-graph  →  langgraph4j-core
          →  langgraph4j-redis-saver
          →  langgraph4j-langchain4j      （LC4jStateSerializer 在这）
          →  spring-boot-starter-web      （如果将来抽 Controller 骨架）
          ❌ 不需要 ai-common
          ❌ 不需要 ai-memory
```

**注意它不依赖 `ai-common`** —— 因为 `ai-common` 至今**不依赖 langgraph4j**
（它是"模型接入 + 观测"，保持零 langgraph4j 依赖是有价值的）。这也是为什么
`ai-graph` 该是独立模块，而不是往 `ai-common` 里塞一个 `graph` 包。

---

## 5. 明确不抽的（同样是这次盘点的产出）

| 不抽 | 为什么 |
|---|---|
| **记忆节点**（`RecallMemoryNode` / `WriteMemoryNode`） | 它们**最像**"每个图都要"，但都依赖 `state.userId()` / `state.messages()`。抽它们必须先定义 `MemoryAwareState` 接口 —— 而第二个图的 state 长什么样**现在只能猜**。**这是"形状未知"，必须等。** |
| **两段式响应格式** | 依赖 state 的 `pendingAction` + 是**对外 API 契约**。抽"判断"就够（见第 3 节） |
| `SCHEMA` 定义模式 | 它就是 state 本身，形状未知 |
| **抽 `BaseAgentState` 基类** | 同上。而且 `AgentState` 已经是基类了，再套一层只会让泛型更难看 |
| `RedisSaver` 装配 | 可抽，但需要 `AgentStateFactory<S>` 参数、且收益只是"少写 6 行"。**等第二个图来验证形状** |

---

## 6. 建议的动作顺序

| 顺序 | 做什么 | 为什么是这个顺序 |
|---|---|---|
| **1** | **`ChatMessageTexts`**（第 2 节） | 重复已经数得出来（5 个类 / 8 个方法）、零状态依赖、顺手修两个隐患。**唯一一项判据完全满足的** |
| **2** | **接线**（25 号的 4 处修复 + 26 号的 `keyNamingStrategy` + `GraphThreadIds`） | 不接线，两个新类和枚举都只是死代码 |
| 3 | 中断判断工具（第 3 节） | 小，顺手 |
| 4 | 跑通一次完整流程，然后回头决定 `ai-graph` 要不要建 | **先验证，再搬** |

**前两步做完，你就有一个真正能跑的图了。** 第 3、4 步是在那之后的事。

---

## 一句话总结

> **这次盘点的结论不是"能抽的很多"，而是"能抽的只有一项"。**
>
> 8 个候选里，只有 **`ChatMessageTexts`** 满足"形状已被反复验证"这个判据 ——
> 因为它**已经被写了 5 遍**。其余候选要么依赖 state 的形状（该等），
> 要么是对外契约（该谨慎），要么只有一个使用者（该等）。
>
> **判据不是"听起来通用不通用"，而是"数得出来几个使用者"。**
