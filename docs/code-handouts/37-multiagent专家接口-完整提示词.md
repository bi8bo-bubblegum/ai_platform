# 37 · 专家接口：每个专家一份完整提示词 ⚠️ 未采用

> ⚠️ **本文档的方案【未被采用】** —— 落地前改回了「统一骨架 + 每个专家自己的准则」，
> 见 **[38 号](38-multiagent专家接口最终版-骨架与准则.md)**。
>
> 保留本文档是因为它记录了一个**考虑过但否决**的方案，以及否决它的理由。
> 最关键的一条：**骨架方案的 `{{tools}}` / `{{memory}}` 住在框架的注解里，物理上漏不了 ——
> 所以那整个"占位符校验"机制都不需要了。**
>
> **要照着敲的话看 38 号。**

---

> **前置**：36 号（建图最终版）已落地。
>
> **本单解决的事**：现在所有专家共用一份 `@SystemMessage` 骨架，只能改 `{{role}}` 一个变量。
> 像"订单助手不要展示完整手机号""数据分析助手只读不写"这类**工作准则没有地方放**。
>
> **本单的目标**：每个专家提供**自己的完整系统提示词**。

---

## 0. 先说清代价：完整提示词换来自由，也换走了"一致性保障"

| | 统一骨架（方案 A） | **完整提示词（本单）** |
|---|---|---|
| 灵活性 | 只能改一个"准则"段落 | **完全自由** |
| 工具/记忆注入点 | 框架保证 | **靠专家自己写对** |
| 漏写注入点 | 不可能 | ⚠️ **可能，且静默** |
| 输出格式一致性 | 框架保证 | 框架保证（`ExpertProposal` 是结构化返回） |

**注意最后一行**：输出格式**不靠提示词保证** —— 它靠 `ExpertAgent.propose()` 的返回类型
（record）由 LangChain4j 解析。所以"完整提示词"不会破坏结构化输出 ✓

但要防的是另一件事：**漏写占位符 → 模型看不到工具 → 静默退化**。

**本单的对策：建图时校验占位符存在，缺了直接抛异常。** 三个占位符：

| 占位符 | 缺了会怎样 |
|---|---|
| `{{tools}}` | ★ **模型永远不提议工具**，专家退化成纯聊天 —— **静默** |
| `{{memory}}` | 长期记忆注入不进去，助手"不认识用户了" —— **静默** |
| `{{role}}` | 提示词里显示 `{{role}}` 字面量 —— 可见，但不报错 |

---

## 1. 新增 `expert/Expert.java`

```java
package com.best.multiagent.expert;

/**
 * 一个专家的定义。
 *
 * <p><b>它只描述"这个专家是谁"，不描述"它能调什么工具"</b> ——
 * 工具清单的唯一来源是 MCP 的 {@code tools/list}（按 token 过滤），
 * 在这里静态声明一份就等于维护第二份真相，必然和网关的授权漂移。
 * 用哪个 MCP 客户端（{@code ExpertRouting.edgeKey()}）就已经决定了它能用哪些工具。
 */
public interface Expert {

    /**
     * 这个专家的<b>完整系统提示词</b>。
     *
     * <p><b>必须同时包含 {@code {{tools}}} 和 {@code {{memory}}} 两个占位符</b> ——
     * 否则 {@link com.best.multiagent.graph.MultiAgentGraphFactory} 会在<b>建图时</b>抛异常。
     *
     * <p><b>为什么是"必须"，而不是"可选"。</b> 漏了它们的后果是【静默】的：
     * 模型看不到工具清单 → 永远不提议工具 → 这个专家退化成一个纯聊天机器人；
     * 而日志、响应、状态全都没有任何异常。<b>把静默失效变成一个启动期的硬错误，
     * 是这份校验唯一的、也是全部的价值。</b>
     *
     * <p>占位符由 {@code ExpertNode} 在运行期替换（每次请求的值都不同）：
     * <ul>
     *   <li>{@code {{tools}}} —— 这个专家当前可调用的工具清单（name + 危险等级 + 参数结构）</li>
     *   <li>{@code {{memory}}} —— 长期记忆召回的用户信息，可能为空字符串</li>
     * </ul>
     *
     * <p><b>它们放哪儿由你决定</b> —— 开头、结尾、分小节都行。
     * 甚至可以把 {@code {{tools}}} 写成"可用工具如下：\n{{tools}}"。框架不做任何排版假设。
     */
    String systemPrompt();
}
```

---

## 2. 新增三个实现（`expert/` 包）

### 2.1 `expert/OrderExpert.java`

```java
package com.best.multiagent.expert;

/**
 * 订单助手。
 *
 * <p>它的准则是<b>隐私与准确性</b>：订单查询会返回手机号、证件号、金额，
 * 这些不该原样展示给用户，也不该原样写进后续的对话上下文。
 */
public class OrderExpert implements Expert {

    @Override
    public String systemPrompt() {
        return """
                你是订单助手，负责帮用户查询和解释订单相关信息。

                ## 你可以调用的工具
                {{tools}}

                ## 已知的用户信息（可能为空）
                {{memory}}

                ## 工作准则
                - 涉及隐私字段（手机号、证件号、支付交易号）时，只显示后四位，其余用 * 替代
                - 金额必须带币种，不要只给数字
                - 用户说"我的订单"但没提供订单号时，先询问他最近一笔订单的特征（时间/金额/出发地）
                  —— 不要凭空猜一个订单号去查
                - 如果查询结果为空，直接说"没有查到"，不要编造订单

                ## 输出要求
                请给出给用户的处理意见。
                需要调用工具时，在 tool 字段填工具名、arguments 填参数对象；
                不需要调用时 tool 填 NONE、arguments 填空对象。
                一次只提议一个工具，看到结果后再决定是否需要下一个。
                不要假装已经执行了 —— 真正的执行由后续环节完成。
                """;
    }
}
```

### 2.2 `expert/AnalysisExpert.java`

```java
package com.best.multiagent.expert;

/**
 * 数据分析助手。
 *
 * <p>它的准则是<b>只读</b>：它能拿到的是 SQL 执行工具，而"生成 SQL"这件事
 * 天然容易滑向写操作。准则要把它钉死在 SELECT 上。
 */
public class AnalysisExpert implements Expert {

    @Override
    public String systemPrompt() {
        return """
                你是数据分析助手，负责把用户的问题翻译成查询、并解释查询结果。

                ## 你可以调用的工具
                {{tools}}

                ## 已知的用户信息（可能为空）
                {{memory}}

                ## 工作准则
                - 只生成 SELECT 查询。任何写操作（INSERT / UPDATE / DELETE / DDL）都不要生成 ——
                  如果用户要求修改数据，说明这里只能查询，让他走别的流程
                - 不要用 SELECT *，只取需要解释的列
                - 查询前如果不知道表结构，先用工具查看有哪些表和字段，再写查询
                - 解释结果时给出数字本身，不要下"业务上说明了什么"的结论 ——
                  那是用户的判断，不是你的
                - 结果集很大时（超过 100 行），说明这一点，不要逐行罗列

                ## 输出要求
                请给出给用户的处理意见。
                需要调用工具时，在 tool 字段填工具名、arguments 填参数对象；
                不需要调用时 tool 填 NONE、arguments 填空对象。
                一次只提议一个工具，看到结果后再决定是否需要下一个。
                不要假装已经执行了 —— 真正的执行由后续环节完成。
                """;
    }
}
```

### 2.3 `expert/KnowledgeExpert.java`

```java
package com.best.multiagent.expert;

/**
 * 知识库助手。
 */
public class KnowledgeExpert implements Expert {

    @Override
    public String systemPrompt() {
        return """
                你是知识库助手，负责在已有文档里找答案。

                ## 你可以调用的工具
                {{tools}}

                ## 已知的用户信息（可能为空）
                {{memory}}

                ## 工作准则
                - 回答必须基于检索到的文档内容，不要补充自己的判断
                - 引用时说明来自哪篇文档，让用户可以自己去核对
                - 检索不到相关内容时直说"文档里没有"，不要用常识回答 ——
                  这个助手的价值就在于"只答文档里有的"
                - 如果检索到的内容相互矛盾，把矛盾指出来，不要替用户选一个

                ## 输出要求
                请给出给用户的处理意见。
                需要调用工具时，在 tool 字段填工具名、arguments 填参数对象；
                不需要调用时 tool 填 NONE、arguments 填空对象。
                一次只提议一个工具，看到结果后再决定是否需要下一个。
                不要假装已经执行了 —— 真正的执行由后续环节完成。
                """;
    }
}
```

> **三个实现里 `## 输出要求` 那一段是重复的。** 这是**刻意的**：本单选择了"完整提示词"，
> 就意味着放弃了框架对提示词的一致性保障。
> **如果你后来觉得这段重复太碍眼**，可以把它抽成一个 `ExpertPrompts.STANDARD_OUTPUT_RULES`
> 常量让三个实现引用 —— 但那是**你自己**的选择，框架不替你做。

---

## 3. 改造 `graph/ExpertRouting.java`

```java
package com.best.multiagent.graph;

import com.best.multiagent.expert.Expert;

public interface ExpertRouting {

    /**
     * 边上的 key。<b>它同时是三样东西</b>：
     * 专家节点的名字、supervisor 条件边的 key、`ai.mcp.servers` 里的 key。
     */
    String edgeKey();

    /**
     * 中文角色名。<b>它现在只用于日志和回给用户的消息前缀，不再进提示词</b> ——
     * 提示词完全由 {@link #expert()} 自己写（包括"你是谁"那句）。
     *
     * <p><b>返回 {@code null} 表示"这个枚举值不是专家"</b>（比如 {@code FINISH}）。
     */
    String expertRole();

    /**
     * 这个专家的定义（主要是它的完整提示词）。
     *
     * <p><b>返回 {@code null} 表示"不是专家"</b> —— 和 {@link #expertRole()} 的判断保持一致。
     * 两者必须同时为 null 或同时非 null（工厂只检查 {@code expertRole()}）。
     */
    Expert expert();
}
```

---

## 4. 改造 `agent/SupervisorAgent.java`（枚举持有 Expert）

```java
package com.best.multiagent.agent;

import com.best.multiagent.expert.*;
import com.best.multiagent.graph.ExpertRouting;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    enum Routing implements ExpertRouting {

        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER("订单助手", new OrderExpert()),

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS("数据分析助手", new AnalysisExpert()),

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE("知识库助手", new KnowledgeExpert()),

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH(null, null);                    // ★ 两个都是 null

        private final String role;
        private final Expert expert;

        Routing(String role, Expert expert) {
            this.role = role;
            this.expert = expert;
        }

        @Override
        public String edgeKey() {
            return name().toLowerCase();
        }

        @Override
        public String expertRole() {
            return role;
        }

        @Override
        public Expert expert() {
            return expert;
        }
    }

    @SystemMessage("""
            你是任务调度器。根据对话内容，判断下一步该交给谁。

            判断依据：
            - 按【请求涉及的领域】选，不要按"是查询还是修改"选
            - 如果最近的对话里已经有明确的结论或回答，就选 FINISH
            """)
    Routing route(@UserMessage String conversation);
}
```

> **`new OrderExpert()` 写在枚举常量里是安全的** ——
> 这些实现类**只有提示词、没有任何依赖**（不需要注入 Bean）。
> 如果哪天某个 expert 需要访问 Service，就不能再 `new` 了 ——
> 那时要改成"枚举持有 Class + 工厂从容器取 Bean"。**现在不需要。**

---

## 5. 改造 `agent/ExpertAgent.java`

```java
package com.best.multiagent.agent;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

public interface ExpertAgent {

    /**
     * 专家处理一次请求，产出「给用户的意见」+「可选的工具调用提议」。
     *
     * <p><b>系统提示词由调用方拼好传进来，不再是编译期固定的注解模板。</b>
     * 原因：本单改成"每个专家一份完整提示词"，而注解是编译期固定的 ——
     * 只能靠一个占位符把整个提示词接进来。
     *
     * @param systemPrompt 已经替换好 {@code {{tools}}} 和 {@code {{memory}}} 的完整提示词
     */
    @SystemMessage("{{systemPrompt}}")
    ExpertProposal propose(@V("systemPrompt") String systemPrompt,
                           @UserMessage String request);

    /**
     * 专家的产出。
     *
     * <p>{@code arguments} 用 {@code Map<String, Object>} 而不是 String：
     * MCP 那边要的是 JSON 文本，但让模型把 JSON 作为<b>字符串字段值</b>输出
     * 会触发双重转义，模型经常写错。用 Map 则由 Jackson 直接序列化。
     */
    record ExpertProposal(

            @Description("给用户的处理意见或结论，用中文，简洁")
            String reply,

            @Description("要调用的工具名，必须从上面列出的工具清单里选；不需要调用工具时填 NONE")
            String tool,

            @Description("工具调用参数，JSON 对象；不需要调用工具填空对象")
            Map<String, Object> arguments
    ) {
    }
}
```

> **注意 `@SystemMessage("{{systemPrompt}}")` 这里有个细节**：
> 整个系统消息就是**一个占位符**，由方法参数 `@V("systemPrompt")` 填充。
> 这样保留了 LangChain4j 的占位符机制（由框架做替换，而不是我们在 Java 里 `String.replace`），
> 同时让提示词内容完全动态。
>
> **多行字符串作为占位符的值没有问题** —— LangChain4j 直接替换，不做转义。

---

## 6. 改造 `node/ExpertNode.java`

```java
package com.best.multiagent.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.message.ChatMessageTexts;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.graph.ExpertRouting;
import com.best.multiagent.state.MultiAgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * 专家节点：让一位专家就当前对话提出一次"处理意见 + 工具提议"。
 *
 * <p><b>构造只收一个 {@link ExpertRouting}</b> —— 而不是把它的
 * {@code edgeKey} / {@code expertRole} 拆开传。
 * 理由：这些字段以后还会加（这一单就加了 {@code expert()}），
 * 每加一个就改一次构造签名，是"拆同一个对象的多个字段"的典型代价。
 */
@Slf4j
public class ExpertNode implements NodeAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExpertRouting routing;
    private final ExpertAgent expertAgent;
    private final McpToolCatalog toolCatalog;

    public ExpertNode(ExpertRouting routing, ExpertAgent expertAgent, McpToolCatalog toolCatalog) {
        this.routing = routing;
        this.expertAgent = expertAgent;
        this.toolCatalog = toolCatalog;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String role = routing.expertRole();
        String clientKey = routing.edgeKey();

        String request = ChatMessageTexts.lastUserText(state.messages());
        List<McpTool> tools = toolCatalog.list(clientKey);

        // 把动态部分填进专家自己的提示词。
        // 替换的是两个【框架约定】的占位符 —— 它们的存在由建图工厂校验过，
        // 所以这里不需要判"有没有占位符"（那是启动期就该拦住的事）
        String systemPrompt = routing.expert().systemPrompt()
                .replace("{{tools}}", renderTools(tools))
                .replace("{{memory}}", state.memoryContext());

        ExpertAgent.ExpertProposal proposal;
        try (var ignored = AiUserContext.open(state.userId())) {
            proposal = expertAgent.propose(systemPrompt, request);
        } catch (Exception e) {
            log.error("专家 {} 处理失败", role, e);
            return Map.of(
                    MultiAgentState.MESSAGES, AiMessage.from("[" + role + "] 处理失败：" + e.getMessage()),
                    MultiAgentState.PROPOSED_TOOL, ""
            );
        }

        String tool = normalizeTool(proposal.tool(), tools, request);
        log.info("专家 {} 提议工具: {} 参数: {}", role, tool, proposal.arguments());

        return Map.of(
                MultiAgentState.MESSAGES, AiMessage.from("[" + role + "] " + safe(proposal.reply())),
                MultiAgentState.PROPOSED_TOOL, tool,
                MultiAgentState.PROPOSED_ARGS, toJson(proposal.arguments()),
                MultiAgentState.PROPOSED_BY, clientKey
        );
    }

    /**
     * 校验模型给的工具名。<b>模型会编造工具名（幻觉）</b>，
     * 编出来的名字直接拿去执行，要等到执行节点才暴露 ——
     * 那时已经烧掉一次模型调用和一次网络往返。
     */
    private String normalizeTool(String proposed, List<McpTool> available, String request) {
        if (proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())) {
            return NO_TOOL;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if (!exists) {
            log.warn("专家 {} 提议了一个不在清单里的工具: [{}]。可用: {}。请求: {}",
                    routing.expertRole(), trimmed, available.stream().map(McpTool::name).toList(), request);
            return NO_TOOL;
        }
        return trimmed;
    }

    private String renderTools(List<McpTool> tools) {
        if (tools.isEmpty()) {
            return "(本次没有可用工具，如果确实需要执行操作，请说明你缺少什么工具)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            sb.append("- ").append(tool.name())
                    .append(" [危险等级: ").append(tool.risk()).append("]\n")
                    .append("  说明: ").append(safe(tool.description())).append('\n')
                    .append("  参数: ").append(tool.parameters()).append('\n');
        }
        return sb.toString();
    }

    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("工具参数序列化失败，按空对象处理: {}", args, e);
            return "{}";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
```

---

## 7. 改造 `graph/MultiAgentGraphFactory.java`（加占位符校验）

只加**一段校验**，其余不变：

```java
        for (E routing : routingEnum.getEnumConstants()) {
            String key = routing.edgeKey();
            String role = routing.expertRole();

            if (role == null) {
                supervisorRoutes.put(key, GraphNodes.WRITE_MEMORY);
                continue;
            }

            // ★ 建图期校验：专家的提示词必须包含两个动态占位符。
            //
            // 为什么必须是【硬错误】而不是 warn：漏了 {{tools}} 的后果是
            // "模型看不到工具清单 → 永远返回 NONE → 这个专家退化成纯聊天"，
            // 而日志、响应、状态全程没有任何异常。
            // 把它变成启动期的一个明确报错，是这段校验唯一的、也是全部的价值。
            String prompt = routing.expert() == null ? null : routing.expert().systemPrompt();
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException(
                        "专家 [" + key + "] 的 expert() 或 systemPrompt() 为空");
            }
            if (!prompt.contains("{{tools}}")) {
                throw new IllegalArgumentException(
                        "专家 [" + key + "] 的系统提示词缺少 {{tools}} 占位符 —— "
                        + "漏了它模型将看不到任何工具，这个专家会退化成纯聊天且不会有任何报错");
            }
            if (!prompt.contains("{{memory}}")) {
                throw new IllegalArgumentException(
                        "专家 [" + key + "] 的系统提示词缺少 {{memory}} 占位符 —— "
                        + "漏了它长期记忆注入不进去，助手会\"不认识用户\"且不会有任何报错");
            }

            graph.addNode(key, node(new ExpertNode(routing, expertAgent, toolCatalog)));
            graph.addEdge(key, GraphNodes.TOOL_EXECUTE);
            supervisorRoutes.put(key, key);
            toolRoutes.put(key, key);
            expertCount++;
        }
```

> 注意 `new ExpertNode(routing, expertAgent, toolCatalog)` —— **3 个参数**（原来是 4 个）。
> 泛型枚举 `E` 可以直接当 `ExpertRouting` 用（因为 `E extends ExpertRouting`）。

---

## 8. 验收

### 8.1 编译 + 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

启动日志应该还是 `建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH`。

### 8.2 ★ 验证校验真的生效（本单特有）

**故意把 `OrderExpert` 里的 `{{tools}}` 删掉**，重启 —— 应该启动就失败：

```
IllegalArgumentException: 专家 [order] 的系统提示词缺少 {{tools}} 占位符 ——
漏了它模型将看不到任何工具，这个专家会退化成纯聊天且不会有任何报错
```

**改回来。** 这一条验证的是"静默失效变成了硬错误"，是本单最重要的保障。

### 8.3 回归：原来的四条用例必须全过

```bash
# ① 低危工具 → 直接执行
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'
# 期待 {"status":"done"}

# ② 高危工具 → 停下
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'
# 期待 {"status":"waiting_approval"}

# ③ 批准 → 只执行一次
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
grep -c "工具执行成功" <日志>       # 必须恰好 1

# ④ 连续两次高危 → 第二次还要停
curl -s -X POST http://localhost:8081/agent/chat ... -d '{...,"sessionId":"r2"}'
# 期待仍是 waiting_approval
```

**硬指标**：

```bash
grep -c "调度决定" <日志>       # ★ 仍须 ≤ 2
```

### 8.4 验证"提示词真的按专家走了"

`OrderExpert` 的准则里写了"只显示后四位"。问一句会返回手机号的订单 ——
**回答里应该看不到完整手机号**。如果能看到，说明这份提示词没生效（或者模型没遵守，
那就改准则的措辞，而不是改框架）。

---

## 9. 自查清单

**编译期**
- [ ] `ExpertRouting` 有三个方法，`Routing` 三个都实现了
- [ ] `FINISH(null, null)` —— 两个参数都传了 null
- [ ] `ExpertAgent.propose` 现在只有两个参数（`systemPrompt` + `request`）
- [ ] `ExpertNode` 的构造是 3 个参数

**行为**
- [ ] 启动日志 `专家数=3`
- [ ] 8.2 的校验验证通过（删掉占位符 → 启动失败）
- [ ] 8.3 的四条用例全过 + `调度决定 ≤ 2`

**边界**
- [ ] 三个专家的提示词都包含 `{{tools}}` 和 `{{memory}}`

---

## 10. 本单之后，加一个 expert 变成这样

| # | 位置 | 改动 |
|---|---|---|
| 1 | `expert/` 下新建一个 `XxxExpert implements Expert` | 写完整提示词 |
| 2 | `SupervisorAgent.Routing` 加一行 | `@Description(...) XXX("角色名", new XxxExpert())` |
| 3 | `application.yml` 加 token | |

**从 2 处变成 3 处** —— 多的那一处是"新建一个类"，它本来就不该省：
**提示词是业务知识，值得拥有自己的文件。**

---

## 11. 留一个提醒：完整提示词的"隐形代价"

采用完整提示词之后，**框架不再保证任何"提示词里有什么"**。三个实现里那段重复的
`## 输出要求` 就是第一个信号 —— 它现在靠你在三个文件里各写一遍。

**如果哪天你发现：**

- 三份提示词里某段开始不一致（一个改了另一个没改）
- 新增第四个专家时，你记不清"必须写哪几段"

**那说明"骨架 + 准则"那个方案（本单第 0 节表格里的方案 A）可能更适合你。**

**切换的成本很低** —— 只需要：给 `Expert` 加一个 `rules()` 方法，
把 `@SystemMessage` 换成"骨架 + `{{rules}}` 占位符"，三个实现的提示词各自砍掉重复部分。

**所以这不是一个不可逆的决定。** 先用完整提示词，等你真的感觉到重复的痛，再换。
