# 38 · 专家接口最终版：统一骨架 + 每个专家自己的准则

> **本文档取代 `37-专家接口-完整提示词`**（那份是"考虑过但否决"的方案，保留作记录）。
>
> **前置**：36 号（建图最终版）已落地。
>
> **本单的目标**：让每个专家有自己的工作准则，**同时不放弃框架对提示词骨架的保障**。

---

## 0. 为什么不走"完整提示词"

上一版计划让每个专家提供**完整系统提示词**，结果发现三件事：

| # | 问题 | 为什么是问题 |
|---|---|---|
| 1 | **"输出要求"那段是框架契约** | `一次只提议一个工具` / `tool 填 NONE` / `不要假装已执行` —— 这些是**框架调用约定**，不是业务知识。放进业务文件后，**框架改一次要改 N 个文件，漏一个就漂移** |
| 2 | **`role` 会重复** | 专家要在提示词里手写"你是订单助手"，而枚举里已经有一份 —— 两处就可能不一致 |
| 3 | ★ **骨架方案不需要那个校验** | 完整提示词方案必须加"占位符校验"（漏 `{{tools}}` 会静默退化成纯聊天）。而**骨架方案的占位符住在框架的 `@SystemMessage` 注解里 —— 物理上漏不了** |

**第 3 条最关键**，值得单独说：

> **完整提示词方案之所以需要校验，是因为它的"自由"把框架的约束外包给了业务文件。**
> 一旦外包出去，就只能靠校验补救。
>
> **骨架方案把约束收回框架内部 —— 于是那整个校验机制都不需要了。**

**留一个观察**：这个判断是**在"想象第二、第三个专家"时才浮现的**。
一个专家看不出重复；三个专家，重复立刻可见。
—— 这是"数得出来几个使用者"那条判据的实操形式，**你和它打过一次照面了**。

---

## 1. `expert/Expert.java`

```java
package com.best.multiagent.expert;

/**
 * 一个专家的定义。
 *
 * <p><b>它只描述"这个专家自己的工作准则"，不描述其他任何东西</b>：
 * <ul>
 *   <li><b>不描述"能调什么工具"</b> —— 工具清单的唯一来源是 MCP 的 {@code tools/list}（按 token 过滤）。
 *       在这里声明一份就是第二份真相，必然和网关的授权漂移。
 *       {@code ExpertRouting.edgeKey()}（用哪个 MCP 客户端）已经决定了它能用哪些工具。</li>
 *   <li><b>不描述"你是谁 / 怎么输出"</b> —— 那些属于统一的提示词骨架，
 *       由 {@code ExpertAgent} 的 {@code @SystemMessage} 提供。
 *       骨架里有 {@code {{tools}}} 和 {@code {{memory}}} 两个动态占位符，
 *       <b>由框架保证存在</b>（它们写在注解里，漏不了）。</li>
 * </ul>
 *
 * <p><b>所以实现类里只该有"这个专家独有的判断"</b> —— 边界、禁忌、优先级。
 * 就像给新人写岗位须知：不重复公司制度，只写这个岗位特别注意的地方。
 */
public interface Expert {

    /**
     * 这个专家的<b>工作准则</b>，会填进统一提示词骨架的 {@code {{rules}}} 位置。
     *
     * <p><b>写什么：</b>这个专家独有的判断 —— 什么该做、什么绝不能做、遇到边界情况怎么办。
     *
     * <p><b>不用写什么：</b>
     * <ul>
     *   <li>"你是XX助手" —— 骨架里由 {@code {{role}}} 提供</li>
     *   <li>"需要调用工具时 tool 字段填什么" —— 骨架的"输出要求"里有，且所有专家一致</li>
     *   <li>"有哪些工具可用" —— 骨架里由 {@code {{tools}}} 注入</li>
     * </ul>
     *
     * <p><b>返回空字符串是允许的</b> —— 表示这个专家没有额外准则，完全按骨架来
     * （比如一个纯粹的"换个视角看问题"的专家）。
     */
    String rules();
}
```

---

## 2. 三个实现（`expert/` 包）

**注意每个文件有多短** —— 它们只写"这个岗位特别注意的地方"。

### 2.1 `expert/OrderExpert.java`

```java
package com.best.multiagent.expert;

/**
 * 订单助手。
 *
 * <p>它的准则是<b>隐私与准确性</b>：订单查询会返回手机号、证件号、金额，
 * 这些不该原样展示给用户。
 */
public class OrderExpert implements Expert {

    @Override
    public String rules() {
        return """
                - 涉及隐私字段（手机号、证件号、支付交易号）时，只显示后四位，其余用 * 替代
                - 金额必须带币种，不要只给数字
                - 用户说"我的订单"但没提供订单号时，先询问他最近一笔订单的特征（时间/金额/出发地）
                  —— 不要凭空猜一个订单号去查
                - 查询结果为空时直接说"没有查到"，不要编造订单
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
 * <p>它的准则是<b>只读</b>：它能拿到 SQL 执行工具，而"生成 SQL"这件事天然容易滑向写操作，
 * 准则要把它钉死在 SELECT 上。
 */
public class AnalysisExpert implements Expert {

    @Override
    public String rules() {
        return """
                - 只生成 SELECT 查询。任何写操作（INSERT / UPDATE / DELETE / DDL）都不要生成 ——
                  用户要求修改数据时，说明这里只能查询
                - 不要用 SELECT *，只取需要解释的列
                - 不知道表结构时，先查看有哪些表和字段，再写查询
                - 解释结果时给出数字本身，不要下"业务上说明了什么"的结论 ——
                  那是用户的判断，不是你的
                - 结果集超过 100 行时说明这一点，不要逐行罗列
                """;
    }
}
```

### 2.3 `expert/KnowledgeExpert.java`

```java
package com.best.multiagent.expert;

/** 知识库助手。准则是"只答文档里有的"。 */
public class KnowledgeExpert implements Expert {

    @Override
    public String rules() {
        return """
                - 回答必须基于检索到的文档内容，不要补充自己的判断
                - 引用时说明来自哪篇文档，让用户可以自己核对
                - 检索不到相关内容时直说"文档里没有"，不要用常识作答 ——
                  这个助手的价值就在于"只答文档里有的"
                - 检索到的内容相互矛盾时把矛盾指出来，不要替用户选一个
                """;
    }
}
```

---

## 3. `graph/ExpertRouting.java`

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
     * 中文角色名。<b>它会填进提示词骨架的 {@code {{role}}} 位置</b>
     * （"你是{{role}}专家"），所以专家实现里不用再写一遍"我是谁"。
     *
     * <p><b>返回 {@code null} 表示"这个枚举值不是专家"</b>（比如 {@code FINISH}）。
     */
    String expertRole();

    /**
     * 这个专家的工作准则。
     *
     * <p><b>返回 {@code null} 表示"不是专家"</b> —— 和 {@link #expertRole()} 的判断一致。
     */
    Expert expert();
}
```

---

## 4. `agent/SupervisorAgent.java`

```java
package com.best.multiagent.agent;

import com.best.multiagent.expert.*;
import com.best.multiagent.graph.ExpertRouting;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    /**
     * 路由目标。<b>这个枚举就是"这张图有哪些专家"的唯一声明</b> ——
     * 节点、边、路由表全部由它生成。
     *
     * <p><b>划分依据是"属于哪个服务"</b>，而不是"有没有副作用"：
     * 每个专家的工具由<b>网关分发的 token</b> 决定，而 token 是按服务授权的。
     * 按"有没有副作用"分会出现死局 —— 判定为查询 → 交给某个专家 →
     * 但那个专家的 token 根本没有这个服务的工具，他手里没工具，只能编答案。
     *
     * <p><b>{@code @Description} 写的话术直接决定路由准确率</b>
     * （LangChain4j 的 {@code EnumOutputParser.formatInstructions()} 会把它拼进提示词）。
     */
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

> **`new OrderExpert()` 写在枚举常量里是安全的** —— 这些实现类**只有准则、没有任何依赖**。
> 如果哪天某个专家需要访问 Service，就不能再 `new` 了 ——
> 那时改成"枚举持有 Class + 工厂从容器取 Bean"。**现在不需要。**

---

## 5. `agent/ExpertAgent.java` ★ 回到骨架

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
     * <p><b>这是一个【统一骨架】，所有专家共用。</b>
     * 骨架里有三个动态占位符，由方法参数填充：
     * <ul>
     *   <li>{@code {{role}}} —— 专家角色名（来自路由枚举）</li>
     *   <li>{@code {{rules}}} —— 这个专家自己的工作准则（来自 {@code Expert.rules()}）</li>
     *   <li>{@code {{tools}}} / {@code {{memory}}} —— 工具清单 / 长期记忆</li>
     * </ul>
     *
     * <p><b>"输出要求"那一段刻意留在骨架里，不交给专家。</b>
     * 因为它是<b>框架契约</b>（怎么表达工具调用、一次提议几个、哪句话不能说），
     * 不是业务知识。放在骨架里 → 框架改一次改一处；
     * 放进 N 个专家实现里 → 改一次要改 N 个文件，漏一个就漂移。
     *
     * <p><b>同理，{@code {{tools}}} 和 {@code {{memory}}} 也由框架保证存在</b> ——
     * 它们写在注解里，物理上漏不了，所以<b>不需要任何建图期校验</b>。
     */
    @SystemMessage("""
            你是{{role}}。

            ## 你的工作准则
            {{rules}}

            ## 你可以调用的工具
            {{tools}}

            ## 已知的用户信息（可能为空）
            {{memory}}

            ## 输出要求
            请针对用户的请求给出处理意见。
            - 需要调用工具时：tool 字段填工具名，arguments 字段填参数对象
            - 不需要调用工具时：tool 填 NONE，arguments 填空对象
            - 一次只提议一个工具；看到它的返回结果后，再决定是否需要下一个
            - 只能从上面的工具清单里选，不要编造工具名
            - 不要假装已经执行了 —— 真正的执行由后续环节完成
            """)
    ExpertProposal propose(@V("role") String role,
                           @V("rules") String rules,
                           @V("tools") String tools,
                           @V("memory") String memory,
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

---

## 6. `node/ExpertNode.java`

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
 * {@code edgeKey} / {@code expertRole} / {@code expert} 拆开传。
 * 理由：这些字段还会加（这一单就加了 {@code expert()}），
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

        // 准则可能为 null（允许"没有额外准则"的专家）→ 用一句占位，避免提示词里出现字面量 "null"
        String rules = routing.expert() == null || routing.expert().rules() == null
                ? "(无额外准则，按通用要求执行)"
                : routing.expert().rules();

        ExpertAgent.ExpertProposal proposal;
        try (var ignored = AiUserContext.open(state.userId())) {
            // 四个占位符都是"传进去就完事" —— 填充由框架的 @SystemMessage 机制做，
            // 我们不做任何字符串拼接
            proposal = expertAgent.propose(
                    role,
                    rules,
                    renderTools(tools),
                    state.memoryContext(),
                    request);
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
                    role(), trimmed, available.stream().map(McpTool::name).toList(), request);
            return NO_TOOL;
        }
        return trimmed;
    }

    private String role() {
        return routing.expertRole();
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

## 7. `graph/MultiAgentGraphFactory.java` —— **删掉占位符校验**

如果你已经按 37 号加了那段校验，**现在删掉它**。工厂的循环恢复成：

```java
        for (E routing : routingEnum.getEnumConstants()) {
            String key = routing.edgeKey();
            String role = routing.expertRole();

            if (role == null) {
                // 不是专家 —— 是"结束"标记。不建节点，只给 supervisor 建一条"指向收尾"的边
                supervisorRoutes.put(key, GraphNodes.WRITE_MEMORY);
                continue;
            }

            // ★ 这里不再需要校验占位符。
            // 因为 {{tools}} / {{memory}} 住在 ExpertAgent 的 @SystemMessage 注解里 ——
            // 那是框架自己的代码，漏不了。
            // （完整提示词那个方案里它们住在业务文件里，所以才需要校验。）
            graph.addNode(key, node(new ExpertNode(routing, expertAgent, toolCatalog)));
            graph.addEdge(key, GraphNodes.TOOL_EXECUTE);
            supervisorRoutes.put(key, key);
            toolRoutes.put(key, key);
            expertCount++;
        }
```

> **`new ExpertNode(routing, expertAgent, toolCatalog)` 不用改** —— 已经传的是 `routing` 本身。

---

## 8. 验收

### 8.1 编译 + 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

启动日志：`建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH`

### 8.2 回归：四条用例 + 硬指标

```bash
# ① 低危工具 → {"status":"done"}
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'

# ② 高危工具 → {"status":"waiting_approval"}
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'

# ③ 批准 → 工具只执行一次
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
grep -c "工具执行成功" <日志>       # 必须恰好 1

# ④ 连续两次高危 → 第二次仍须 waiting_approval

# 硬指标
grep -c "调度决定" <日志>           # ★ 必须 ≤ 2
```

### 8.3 验证"准则真的生效了"

`OrderExpert` 的准则里写了"只显示后四位"。问一句会返回手机号的订单 ——
**回答里应该看不到完整手机号**。

> **如果还能看到**：先改准则的措辞（"必须脱敏"比"应脱敏"有用），**不要改框架**。
> 提示词工程的调整属于业务侧。

### 8.4 验证"骨架真的统一了"

新增第四个专家时，**只需要写准则**：

```java
public class LogisticsExpert implements Expert {
    @Override
    public String rules() {
        return """
                - 运单号要完整展示（用户可以拿去查）
                - 配送异常时先说明异常类型，再给出预计送达时间
                """;
    }
}
```

**不需要写"你是谁""怎么表达工具调用""有哪些工具"** —— 那些骨架里有。

**如果你发现自己在这个文件里想写"你是物流助手"或者"需要调用工具时…"—— 那就是骨架该管的事，写在 `ExpertAgent` 里。**

---

## 9. 自查清单

**编译期**
- [ ] `Expert` 接口只有一个 `rules()` 方法
- [ ] `ExpertRouting` 有三个方法，`Routing` 三个都实现了
- [ ] `FINISH(null, null)` —— 两个参数都传了 null
- [ ] `ExpertAgent.propose` 有 5 个参数（`role` / `rules` / `tools` / `memory` / `request`）
- [ ] **工厂里那段占位符校验已经删掉**（`grep -n "占位符" MultiAgentGraphFactory.java` 应为空）
- [ ] 三个 `Expert` 实现里**没有**"你是XX助手"、没有"输出要求"那类文字

**行为**
- [ ] 启动日志 `专家数=3`
- [ ] 8.2 的四条用例全过 + `调度决定 ≤ 2`
- [ ] 8.3 的脱敏生效

---

## 10. 现在加一个 expert 是什么样

| # | 位置 | 改动 | 写什么 |
|---|---|---|---|
| 1 | `expert/XxxExpert.java` | 新建 | **只写准则**（通常 4~6 行） |
| 2 | `SupervisorAgent.Routing` | 加一行 | `@Description(...) XXX("角色名", new XxxExpert())` |
| 3 | `application.yml` | 加 token | |

**三处，但第 1 处只写"这个岗位特别注意的地方"** —— 骨架、角色、工具、输出格式全都不用碰。

---

## 11. 两个方案的最终对比（留档，避免以后重议）

| | **骨架 + 准则（本单）** | 完整提示词（37 号，未采用） |
|---|---|---|
| 专家的文件里有什么 | 只有准则（4~6 行） | 完整提示词（30+ 行，含重复） |
| 框架契约放哪 | **骨架里，一处** | 每个专家各写一遍 |
| `role` 定义处 | 枚举一处 | 枚举 + 每份提示词各一处 |
| `{{tools}}` 漏了会怎样 | **不可能漏**（在注解里） | 静默退化成纯聊天 |
| 需要建图期校验吗 | **不需要** | 必须 |
| 灵活性 | 骨架结构固定 | 完全自由 |
| 什么情况下才该换成右边的 | — | 某个专家需要**完全不同的输出结构**（而不只是不同的准则） |

**最后一行是关键**：如果哪天真出现一个"结构完全不同"的专家
（比如它不返回 `ExpertProposal`，而是返回一段 XML），
那说明它**不该套在这个骨架里** —— 那时该考虑的是"给它单独一条链路"，而不是"把骨架拆掉"。
