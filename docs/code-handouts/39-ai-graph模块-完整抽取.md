# 39 · `ai-graph` 模块 —— 完整抽取

> **前置**：34/36/37/38 号**尚未落地**（`MultiAgentGraphFactory` 不存在，`Config` 还是 60 行手写版）。
> 本单**不是一个搬移**，是"**把 36/38 号的最终设计 + 模块抽取一起做掉**"。
>
> **本单产出**：新增 `ai-graph` 模块（19 个文件），`ai-multiagent` 瘦成 6 个业务文件。

---

## 0. 本单的三个决定

| # | 决定 | 理由 |
|---|---|---|
| 1 | **`SupervisorNode` 解绑**，引入 `RouteDecider` | 它是**唯一**绑定了业务枚举的框架节点（两处：字段类型 + 兜底 `Routing.FINISH`） |
| 2 | **`createAgent` 变 3 个参数** | 把 `SupervisorAgent` 剔出框架后，它的两个业务依赖点（怎么路由 + 失败去哪）必须显式传回来 |
| 3 | **`MultiAgentGraphFactory` 改成自动装配的 Bean** | `ai-graph` 不会被业务模块组件扫描，必须走 `AutoConfiguration.imports`（照抄 `ai-common`） |

**配置前缀从 `ai.multiagent.*` 改成 `ai.graph.*`** —— 它现在属于框架，不属于这个应用。**你要动 yml 一行。**

---

## 1. 模块结构

```
ai-graph/
├── pom.xml
└── src/main/
    ├── java/com/best/ai_graph/
    │   ├── state/MultiAgentState.java
    │   ├── graph/
    │   │   ├── GraphNodes.java
    │   │   ├── GraphThreadIds.java
    │   │   ├── NamespacedKeyNamingStrategy.java
    │   │   ├── ExpertRouting.java
    │   │   ├── RouteDecider.java              ★ 新增（解绑用）
    │   │   └── MultiAgentGraphFactory.java
    │   ├── expert/Expert.java
    │   ├── agent/ExpertAgent.java
    │   ├── node/
    │   │   ├── SupervisorNode.java            ★ 改（解绑）
    │   │   ├── ExpertNode.java
    │   │   ├── RecallMemoryNode.java
    │   │   ├── WriteMemoryNode.java
    │   │   └── ToolExecuteNode.java
    │   └── config/
    │       ├── GraphProperties.java
    │       └── AiGraphAutoConfiguration.java  ★ 新增
    └── resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports   ★ 新增
```

**注意哪里是 `graph/` 而不是 `node/`**：`ExpertRouting` / `RouteDecider` 是**建图用的契约**，不是节点；`GraphNodes` 是节点**名字表**。它们和节点放一起会让"哪些是图的结构、哪些是图的执行"混掉。

---

## 2. `pom.xml` 改动

### 2.1 父 `pom.xml` —— `<modules>` 加一行

**顺序有讲究**：`ai-graph` 依赖 `ai-memory`，Maven 的 reactor 会自己算依赖顺序，但**列在这里的顺序影响不了正确性、只影响可读性** —— 放在 `ai-memory` 之后：

```xml
    <modules>
        <module>ai-common</module>
        <module>assistant-demo</module>
        <module>ai-memory</module>
        <module>ai-graph</module>          <!-- ★ 新增，必须在 ai-memory 之后 -->
        <module>ai-multiagent</module>
    </modules>
```

### 2.2 `ai-graph/pom.xml`（新建）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.best</groupId>
        <artifactId>ai_platform</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>ai-graph</artifactId>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.bsc.langgraph4j</groupId>
                <artifactId>langgraph4j-bom</artifactId>
                <version>${langgraph4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- 传递依赖带来 ai-common（ChatModelRegister / McpToolCatalog / ChatMessageTexts ...） -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-memory</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>

        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-langchain4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-redis-saver</artifactId>
        </dependency>

        <!-- 只要 autoconfigure + context，不要 web：这是个库，不是服务 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

> **这个 pom 里没有 `spring-boot-maven-plugin`** —— 它是库，不是可执行服务。加了会把 jar 打成 fat jar、还会在 `package` 时报"没有 main class"。

### 2.3 `ai-multiagent/pom.xml` —— 换一个依赖

```xml
        <!-- 把 ai-memory 换成 ai-graph（后者传递带来前者） -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-graph</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
```

其余不动（`langgraph4j-core` / `langchain4j-langchain4j` 保留 —— Controller 直接 import 了 `org.bsc.langgraph4j.*`；`langgraph4j-redis-saver` **可以删**，RedisSaver 只在工厂里用，已经搬走了）。

---

## 3. `ai-graph` 的代码（17 个 java 文件 + 1 个 imports）

### 3.0 `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**文件名必须一字不差**（这是 Spring Boot 3 的约定路径）：

```
com.best.ai_graph.config.AiGraphAutoConfiguration
```

> ⚠️ **这行少了，症状是"业务模块说找不到 `MultiAgentGraphFactory` Bean"** —— 而 `ai-graph` 编译完全正常。
> 和 `ai-common` 当初 `McpAutoConfiguration` 没进 `.imports` 是同一个坑。

---

### 3.1 `state/MultiAgentState.java`

```java
package com.best.ai_graph.state;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多智能体图的 state。
 *
 * <p><b>它是这个框架的 state 契约</b> —— 字段全部由框架节点读写，
 * 所以它属于 {@code ai-graph}，不属于任何一个业务图。
 *
 * <p><b>SCHEMA 里的字段是"图的结构"</b>：加了字段，所有存量 checkpoint 都失效。
 * 所以下面每个常量都标了"谁写、谁读"——改动前先确认没有别人依赖它。
 */
public class MultiAgentState extends AgentState {

    /** 对话消息（<b>appender 通道</b>：就地追加，不是覆盖） */
    public static final String MESSAGES = "messages";

    /** 谁在问。由 Controller 从鉴权信息写入 —— 节点读不到 HTTP 请求，只能靠 state 传 */
    public static final String USER_ID = "userId";

    /** 本次会话 id。作用：给长期记忆构造 memoryId（{@code assistantId::userId::sessionId}） */
    public static final String SESSION_ID = "sessionId";

    /** supervisor 写、条件边读：下一步去哪个节点 */
    public static final String NEXT = "next";

    /** recallMemory 写、专家节点读：渲染好的长期记忆文本 */
    public static final String MEMORY_CONTEXT = "memoryContext";

    /** 审批结果：由 /approve 写入 */
    public static final String APPROVED = "approved";

    /** 专家提议的工具名；{@code NONE} 表示"不需要工具" */
    public static final String PROPOSED_TOOL = "proposedTool";

    /** 专家提议的工具参数（JSON 文本） */
    public static final String PROPOSED_ARGS = "proposedArgs";

    /** 谁提的提议（= 那个专家的 clientKey）。toolExecute 靠它选 MCP 客户端，条件边靠它决定回哪个专家 */
    public static final String PROPOSED_BY = "proposedBy";

    /**
     * <b>"当前这个提议已经被人回答过了"。</b>
     *
     * <p><b>它和 {@link #APPROVED} 是两件事，不能合并。</b> {@code APPROVED} 默认 false，
     * 分不清"还没问过"和"用户拒绝了" —— 只看 {@code APPROVED} 会让
     * {@code interrupt()} 在 resume 之后<b>再次中断</b>，图卡死。
     *
     * <p><b>它必须被重置</b>：表示的是"<b>当前这个提议</b>已被回答"，
     * 不是"这个人曾经回答过"。不重置 → 第二次危险操作<b>绕过审批直接执行</b>，且日志上只看到"执行成功"。
     */
    public static final String APPROVAL_DECIDED = "approvalDecided";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MESSAGES, Channels.<ChatMessage>appender(ArrayList::new),
            USER_ID, Channels.base(() -> ""),
            SESSION_ID, Channels.base(() -> ""),
            NEXT, Channels.base(() -> ""),
            MEMORY_CONTEXT, Channels.base(() -> ""),
            APPROVED, Channels.base(() -> false),
            PROPOSED_TOOL, Channels.base(() -> ""),
            PROPOSED_ARGS, Channels.base(() -> "{}"),
            PROPOSED_BY, Channels.base(() -> ""),
            APPROVAL_DECIDED, Channels.base(() -> false)
    );

    public MultiAgentState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages() {
        // ★ 拿到的是【活引用】（appender 的 reducer 是就地追加）——
        //   传给异步任务前必须 List.copyOf(...)
        return this.<List<ChatMessage>>value(MESSAGES).orElseGet(List::of);
    }

    public String userId()           { return this.<String>value(USER_ID).orElse(""); }
    public String sessionId()        { return this.<String>value(SESSION_ID).orElse(""); }
    public String next()             { return this.<String>value(NEXT).orElse(""); }
    public String memoryContext()    { return this.<String>value(MEMORY_CONTEXT).orElse(""); }
    public boolean approved()        { return this.<Boolean>value(APPROVED).orElse(false); }
    public String proposedTool()     { return this.<String>value(PROPOSED_TOOL).orElse(""); }
    public String proposedArgs()     { return this.<String>value(PROPOSED_ARGS).orElse("{}"); }
    public String proposedBy()       { return this.<String>value(PROPOSED_BY).orElse(""); }
    public boolean approvalDecided() { return this.<Boolean>value(APPROVAL_DECIDED).orElse(false); }
}
```

---

### 3.2 `graph/GraphNodes.java`

```java
package com.best.ai_graph.graph;

/**
 * 图里<b>固定</b>节点的名字。
 *
 * <p><b>这里只放单例节点。</b> 专家节点的名字来自路由枚举（{@link ExpertRouting#edgeKey()}）——
 * 它们的数量可变，且已经有唯一来源。
 *
 * <p><b>为什么值得单独一个类。</b> 这些名字有两类使用方，而且必须一致：
 * 建图时 {@code addNode(name, ...)}，以及 Controller 判断"这次是不是停在中断点上等人批准"。
 * 原来两处各写一遍字符串，改名时必漏一处 ——
 * <b>而漏了的症状是"审批静默失效"</b>：Controller 判不出"停在闸门上"，
 * 就把等待中的请求当"已完成"返回给前端。
 */
public final class GraphNodes {

    /** 长期记忆召回 */
    public static final String RECALL_MEMORY = "recallMemory";

    /** 调度器 */
    public static final String SUPERVISOR = "supervisor";

    /** 工具执行 —— <b>全图唯一的中断点</b> */
    public static final String TOOL_EXECUTE = "toolExecute";

    /** 长期记忆写入 */
    public static final String WRITE_MEMORY = "writeMemory";

    private GraphNodes() {
    }
}
```

---

### 3.3 `graph/GraphThreadIds.java`

```java
package com.best.ai_graph.graph;

/**
 * threadId 的<b>唯一构造点</b>。
 *
 * <p>原来 Controller 的 {@code /chat} 和 {@code /approve} 各拼一次 ——
 * 两处必须产出<b>完全相同</b>的 threadId，否则"第二次请求找不到第一次的 checkpoint"，
 * 症状是审批永远等不到、或者每次都是新会话。**收敛成一个方法，这类 bug 就不存在了。**
 */
public final class GraphThreadIds {

    private static final String SEP = "::";

    private GraphThreadIds() {
    }

    /**
     * @param sessionId 业务侧的会话 id
     * @return checkpoint 的线程 id
     */
    public static String of(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空 —— 它决定这次对话的checkpoint挂在哪个线程下");
        }
        if (sessionId.contains(SEP)) {
            throw new IllegalArgumentException("sessionId 不能包含" + SEP + "，实际：" + sessionId);
        }
        return sessionId;
    }
}
```

> **为什么现在只是"校验后原样返回"而不是拼 `assistantId::userId::sessionId`**：
> assistantId 已经进了 checkpoint 的 **key 命名空间**（见 `NamespacedKeyNamingStrategy`），
> 再拼一次是重复的。**保持原样，是为了不动已有数据的 key。**

---

### 3.4 `graph/NamespacedKeyNamingStrategy.java`

```java
package com.best.ai_graph.graph;

import org.bsc.langgraph4j.checkpoint.KeyNamingStrategy;

/**
 * 给 checkpoint 的 Redis key 加服务命名空间。
 *
 * <p><b>不加的后果是静默串号：</b>多个图应用共用同一台 Redis 时，
 * 不同服务的同名 threadId 会互相覆盖会话状态 ——
 * 表现是"用户看到了别人的对话"，而日志里一切正常。
 */
public class NamespacedKeyNamingStrategy implements KeyNamingStrategy {

    private final String prefix;

    public NamespacedKeyNamingStrategy(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace不能为空");
        }
        this.prefix = "langgraph4j:" + namespace + ":";
    }

    @Override
    public String threadKey(String threadId) {
        return prefix + "thread:" + threadId;
    }

    @Override
    public String threadNameKey(String threadName) {
        return prefix + "thread:name:" + threadName + ":active";
    }

    @Override
    public String checkpointKey(String checkpointId) {
        return prefix + "checkpoint:" + checkpointId;
    }

    @Override
    public String checkpointsKey(String threadId) {
        return threadKey(threadId) + ":checkpoints";
    }

    @Override
    public String keyPrefix() {
        return prefix;
    }
}
```

---

### 3.5 `graph/ExpertRouting.java`

```java
package com.best.ai_graph.graph;

import com.best.ai_graph.expert.Expert;

/**
 * 「路由枚举」要提供的三件事。
 *
 * <p>建图工厂 for 循环遍历枚举、为每个值建节点，它必须能回答：
 * <b>这个值叫什么名字</b>、<b>它是不是一个专家</b>、<b>它的准则是什么</b>。
 *
 * <p><b>用接口而不是反射读注解</b>，因为这三件事都必须在编译期就能表达 ——
 * 反射是在运行期才发现"读不到"，而那时图已经建了一半。
 */
public interface ExpertRouting {

    /**
     * 边上的 key。<b>它同时是三样东西</b>：
     * 专家节点的名字、supervisor 条件边的 key、{@code ai.mcp.servers} 里的 key。
     *
     * <p>三者同一个值是刻意的 —— 它们本来就该一致
     * （"这个专家" = "这条路由" = "这个 token 能看到的工具"），分开配置只会漂移。
     */
    String edgeKey();

    /**
     * 中文角色名，会填进提示词骨架的 {@code {{role}}} 位置。
     *
     * <p><b>返回 {@code null} 表示"这个枚举值不是专家"</b> —— 比如 {@code FINISH}。
     * 建图循环会跳过它建节点，但会给它建一条"指向收尾节点"的边。
     * <b>这样工厂里就不用硬编码 {@code "finish"} 这个字符串了。</b>
     *
     * <p><b>它和 {@code @Description} 是两件事</b>：
     * {@code @Description} 进 <b>supervisor</b> 的提示词（"什么时候该派给我"），
     * {@code expertRole()} 进 <b>这个专家自己</b>的提示词（"你是谁"）。
     * 一个是"别人怎么看它"，一个是"它怎么看自己"，不能复用同一个字符串。
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

### 3.6 `graph/RouteDecider.java` ★ 本单新增 —— 解绑的关键

```java
package com.best.ai_graph.graph;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 调度决策 —— <b>把一个"路由枚举"变成一层间接</b>。
 *
 * <p><b>为什么需要它。</b> 框架节点 {@code SupervisorNode} 原来直接持有业务接口
 * {@code SupervisorAgent}，于是它被迫认识业务枚举。而这个接口只要求一件事：
 * <b>给一串消息，告诉我去哪个节点</b>。
 *
 * <p>写成函数式接口之后：
 * <ul>
 *   <li>框架侧只认 {@link ExpertRouting}（返回枚举的<b>接口</b>形式），不认识任何具体枚举</li>
 *   <li>业务侧用一行 lambda 把 {@code SupervisorAgent.route(...)} 接上</li>
 *   <li><b>零反射、零泛型</b> —— 枚举仍然由 LangChain4j 的 {@code EnumOutputParser}
 *       正常生成提示词（泛型化接口会让它拿不到枚举值，这是否决过的方案）</li>
 * </ul>
 *
 * <p><b>为什么允许 {@code throws Exception}</b>：实现方大概率要调一次模型，
 * 而模型调用失败是常态。把异常交给框架节点统一兜底（走 fallback），
 * <b>比让每个实现自己 try-catch 更不容易漏</b>。
 */
@FunctionalInterface
public interface RouteDecider {

    /**
     * @param messages 当前对话消息（框架保证非 null，可能是空列表）
     * @return 下一步去哪个节点。<b>不返回 null</b>
     */
    ExpertRouting decide(List<ChatMessage> messages) throws Exception;
}
```

---

### 3.7 `expert/Expert.java`

```java
package com.best.ai_graph.expert;

/**
 * 一个专家的定义。
 *
 * <p><b>它只描述"这个专家自己的工作准则"，不描述其他任何东西</b>：
 * <ul>
 *   <li><b>不描述"能调什么工具"</b> —— 工具清单的唯一来源是 MCP 的 {@code tools/list}（按 token 过滤）。
 *       在这里声明一份就是第二份真相，必然和网关的授权漂移。
 *       {@link com.best.ai_graph.graph.ExpertRouting#edgeKey()}（用哪个 MCP 客户端）
 *       已经决定了它能用哪些工具。</li>
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
     * <p><b>返回空字符串或 {@code null} 都是允许的</b> —— 表示这个专家没有额外准则，
     * 完全按骨架来（比如一个纯粹的"换个视角看问题"的专家）。
     */
    String rules();
}
```

---

### 3.8 `agent/ExpertAgent.java`

```java
package com.best.ai_graph.agent;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

/**
 * 专家调用接口 —— <b>全平台一个实现，一个骨架</b>。
 *
 * <p><b>它不属于任何业务图</b>：里面没有一个业务字符串。
 * 业务差异全部通过 {@code {{role}}} / {@code {{rules}}} 两个占位符注入。
 *
 * <p><b>它刻意不挂 {@code .tools(...)}。</b> 挂了之后 LangChain4j 会在专家节点内部
 * <b>当场执行工具</b>，而审批是跨 HTTP 请求的异步动作 ——
 * 工具已经跑了才问"要不要批准"是没有意义的。
 * 所以工具执行业务全部由 {@code ToolExecuteNode} 承担。
 */
public interface ExpertAgent {

    /**
     * 专家处理一次请求，产出「给用户的意见」+「可选的工具调用提议」。
     *
     * <p>骨架里有四个动态占位符，由方法参数填充：
     * <ul>
     *   <li>{@code {{role}}} —— 专家角色名（来自路由枚举的 {@code expertRole()}）</li>
     *   <li>{@code {{rules}}} —— 这个专家自己的工作准则（来自 {@code Expert.rules()}）</li>
     *   <li>{@code {{tools}}} —— 工具清单（按 token 过滤后）</li>
     *   <li>{@code {{memory}}} —— 长期记忆召回文本</li>
     * </ul>
     *
     * <p><b>"输出要求"那一段刻意留在骨架里，不交给专家。</b>
     * 因为它是<b>框架契约</b>（怎么表达工具调用、一次提议几个、哪句话不能说），不是业务知识。
     * 放在骨架里 → 框架改一次改一处；放进 N 个专家实现里 → 改一次要改 N 个文件，漏一个就漂移。
     *
     * <p><b>同理 {@code {{tools}}} / {@code {{memory}}} 也由框架保证存在</b> ——
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

### 3.9 `node/SupervisorNode.java` ★ 解绑后

```java
package com.best.ai_graph.node;

import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.graph.RouteDecider;
import com.best.ai_graph.state.MultiAgentState;
import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * 调度节点：问一次"下一步交给谁"，把结果写进 {@code next}。
 *
 * <p><b>它不认识任何具体枚举</b> —— 只依赖 {@link RouteDecider} 这个函数式接口。
 * 这是它能搬进 {@code ai-graph} 的原因。
 *
 * <p><b>兜底也参数化了。</b> "调度失败去哪"是<b>业务判断</b>
 * （可能收尾、可能转人工、可能重试），所以由调用方通过 {@code fallbackEdgeKey} 传入 ——
 * 而不是像之前那样硬编码一个业务枚举常量。
 */
@Slf4j
public class SupervisorNode implements NodeAction<MultiAgentState> {

    private final RouteDecider decider;

    /** 调度失败时把 {@code next} 写成的值。必须是一个真实存在的路由目标（工厂会校验） */
    private final String fallbackEdgeKey;

    public SupervisorNode(RouteDecider decider, String fallbackEdgeKey) {
        this.decider = decider;
        this.fallbackEdgeKey = fallbackEdgeKey;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        ExpertRouting routing;
        try {
            routing = decider.decide(state.messages());
        } catch (Exception e) {
            log.error("调度失败，走兜底节点 {}", fallbackEdgeKey, e);
            // ★ 这里刻意【不打】"调度决定"日志：
            //   失败不是一次"决定"，它不该计入"调度决定 ≤ 2"那个硬指标。
            return Map.of(
                    MultiAgentState.NEXT, fallbackEdgeKey,
                    MultiAgentState.MESSAGES, AiMessage.from("（调度失败，直接收尾）")
            );
        }
        String edgeKey = routing.edgeKey();
        log.info("调度决定: {} -> {}", routing, edgeKey);
        return Map.of(
                MultiAgentState.NEXT, edgeKey,
                MultiAgentState.MESSAGES, AiMessage.from("（调度：" + routing + "）")
        );
    }
}
```

---

### 3.10 `node/ExpertNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_graph.agent.ExpertAgent;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.ai_graph.state.MultiAgentState;
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
 * 理由：这些字段还会加，每加一个就改一次构造签名 ——
 * 这是"拆同一个对象的多个字段"的典型代价。
 */
@Slf4j
public class ExpertNode implements NodeAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    /** 只做 JSON 序列化，无状态，可以静态复用 */
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

        // 准则可能为 null（允许"没有额外准则"的专家）→ 用一句占位，
        // 避免提示词里出现字面量 "null"
        String rules = routing.expert() == null || routing.expert().rules() == null
                ? "(无额外准则，按通用要求执行)"
                : routing.expert().rules();

        ExpertAgent.ExpertProposal proposal;
        // ★ AiUserContext 是 ThreadLocal —— 异步/多线程场景下模型调用处的 user 会丢。
        //   这里开一个作用域，保证这轮模型调用能带上 userId（token 台账要用）。
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

### 3.11 `node/RecallMemoryNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_graph.state.MultiAgentState;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆召回：把"关于这位用户已知的信息"渲染成一段文本，放进 {@code memoryContext}。
 *
 * <p><b>失败策略是刻意的：召回失败当"没有记忆"，不中断对话。</b>
 * 记忆是增强项，不是主流程 —— 让一次向量检索失败把整轮对话打断，是错误的取舍。
 */
@Slf4j
@RequiredArgsConstructor
public class RecallMemoryNode implements NodeAction<MultiAgentState> {

    private final MemoryRecallService memoryRecallService;

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String userId = state.userId();
        String query = ChatMessageTexts.lastUserText(state.messages());
        if (userId.isBlank() || query.isBlank()) {
            return Map.of(MultiAgentState.MEMORY_CONTEXT, "");
        }
        List<UserMemory> facts;
        try {
            facts = memoryRecallService.recall(userId, query);
        } catch (Exception e) {
            log.warn("长期记忆召回失败，本次跳过。userId={}", userId, e);
            return Map.of(MultiAgentState.MEMORY_CONTEXT, "");
        }
        log.info("长期记忆召回: userId={} 命中={} 条", userId, facts.size());
        return Map.of(MultiAgentState.MEMORY_CONTEXT, render(facts));
    }

    private String render(List<UserMemory> facts) {
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[关于这位用户的已知信息，按时间倒序，若冲突以靠前的为准]\n");
        for (UserMemory m : facts) {
            sb.append("- ").append(m.getContent()).append('\n');
        }
        return sb.toString();
    }
}
```

---

### 3.12 `node/WriteMemoryNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_graph.state.MultiAgentState;
import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.UserMemoryRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆写入：触发一次<b>异步</b>抽取（真正的工作在别的线程里跑）。
 *
 * <p><b>本节点不阻塞图</b>：重的活（LLM 抽取 + 向量化 + 落库，几秒）
 * 全部在 {@code recorder.recordAsync} 内部提交给线程池，
 * 本节点只做"几个 if + 构造 memoryId + 提交任务"，微秒级。
 *
 * <p><b>这是全图唯一不改变 state 的节点</b>（返回 {@code Map.of()}）。
 * 保留在图里（而不是放到 Controller）的<b>唯一理由</b>是：
 * 将来要"按图的状态决定记不记" —— 比如只记成功的、只记某个专家产生的、
 * 或者根据 {@code approvalDecided} 判断"这轮有没有被人拒绝过所以不该记"。
 * <b>这个理由目前一次都没用上；如果一直用不上，它就是一个可以拿掉的节点。</b>
 */
@Slf4j
@RequiredArgsConstructor
public class WriteMemoryNode implements NodeAction<MultiAgentState> {

    private final String assistantId;

    private final UserMemoryRecorder recorder;

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String userId = state.userId();
        if (userId.isBlank()) {
            return Map.of();
        }
        // ★ 用 SessionKey 构造，不手拼字符串。
        //   手拼少一个 "::" 的后果是：SessionKey.parse 在【同步段】抛异常，
        //   被下面的 catch 接成一条 warn —— 表现是"长期记忆永远写不进去，但服务照跑"。
        //   这个写法已经错过一次（SUMMARY_PREFIX 少冒号是第一次，memoryId 是第三次）。
        String memoryId = SessionKey.of(assistantId, userId, state.sessionId()).toMemoryId();
        try {
            // ★ List.copyOf：state.messages() 拿到的是【活引用】
            //   （MESSAGES 的 reducer 是就地追加）。传快照，消除异步线程与主线程
            //   同时操作同一个 list 的可能 —— 那种异常只会打在异步线程的日志里，
            //   被 catch 成一条 error，很难查。
            recorder.recordAsync(memoryId, List.copyOf(state.messages()));
        } catch (Exception e) {
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
        return Map.of();
    }
}
```

---

### 3.13 `node/ToolExecuteNode.java`

```java
package com.best.ai_graph.node;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.ToolRisk;
import com.best.ai_graph.state.MultiAgentState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具执行 —— <b>全图唯一的中断点</b>。
 *
 * <p>它同时实现两个接口，这是审批能够生效的全部机制：
 * <ul>
 *   <li>{@link NodeAction#apply} —— 真正执行工具</li>
 *   <li>{@link InterruptibleAction#interrupt} —— 框架在 {@code apply} <b>之前</b>问一次
 *       "这次要不要停"。<b>返回有值时节点根本不执行</b> ——
 *       所以"停下那一刻工具真的没被执行"。</li>
 * </ul>
 *
 * <p><b>接口名是 {@code InterruptibleAction}（带 i）。</b>
 * {@code InterruptableAction}（不带 i）是个<b>空壳标记接口</b>，一个方法都没有。
 *
 * <p><b>它必须被 {@code AsyncNodeAction.node_async(...)} 包一层</b> ——
 * 只有那个方法内部会 {@code instanceof} 检测 {@code InterruptibleAction}
 * 并用动态代理把可中断性一起带上。工厂里已经包好了。
 */
@Slf4j
public class ToolExecuteNode implements NodeAction<MultiAgentState>, InterruptibleAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private final McpClientRegister register;

    private final McpToolCatalog toolCatalog;

    /** 达到或超过这个等级就需要人工批准 */
    private final ToolRisk approvalMinRisk;

    public ToolExecuteNode(McpClientRegister register, McpToolCatalog toolCatalog, ToolRisk approvalMinRisk) {
        this.register = register;
        this.toolCatalog = toolCatalog;
        this.approvalMinRisk = approvalMinRisk;
    }

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String tool = state.proposedTool();
        if (isNoTool(tool)) {
            return Map.of();
        }

        // ★ 判"被拒绝"必须是两个条件的【与】。
        //   只看 !approved 会把所有低危工具误判成"被拒绝" ——
        //   因为它们从没问过人，approved 也是默认的 false。
        if (state.approvalDecided() && !state.approved()) {
            log.info("工具 {} 被人工拒绝", tool);
            return finishDone(AiMessage.from("[操作被拒绝] 已取消：" + tool
                    + "。如果用户另有需要，可以改用别的方式帮他。"));
        }

        McpClient client = register.get(state.proposedBy());
        if (client == null) {
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}", state.proposedBy(), tool);
            return finishDone(AiMessage.from("[工具 " + tool + " 未能执行] 未配置 "
                    + state.proposedBy() + " 的MCP通道"));
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(state.proposedArgs())
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, state.proposedBy(), abbreviate(text));
            // ★ 走 finishContinue（不是 finishDone）：保留 proposedTool，
            //   因为条件边要靠它判断"这一轮循环要不要继续"。
            return finishContinue(AiMessage.from(
                    "[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text));
        } catch (Exception e) {
            log.error("工具执行异常: tool={} by={} args={}",
                    tool, state.proposedBy(), state.proposedArgs(), e);
            return finishContinue(AiMessage.from("[工具 " + tool + " 执行失败] " + e.getMessage()));
        }
    }

    @Override
    public Optional<InterruptionMetadata<MultiAgentState>> interrupt(String nodeId,
                                                                     MultiAgentState state,
                                                                     RunnableConfig config) {
        String tool = state.proposedTool();
        if (isNoTool(tool)) {
            // 没有工具提议 —— 空转一次，直接放行
            return Optional.empty();
        }

        // ★ 已经被人回答过了 → 放行（本次审批已经完成，该执行就执行）
        if (state.approvalDecided()) {
            return Optional.empty();
        }

        ToolRisk risk = toolCatalog.riskOf(tool);
        if (!risk.atLeast(approvalMinRisk)) {
            log.info("工具 {} 危险等级={} 未达审批阈值={}，直接执行", tool, risk, approvalMinRisk);
            return Optional.empty();
        }
        log.info("工具 {} 危险等级={} 达到审批阈值={}，暂停等待人工批准", tool, risk, approvalMinRisk);
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .reason("工具 " + tool + " 危险等级为 " + risk + "，需要人工批准").build());
    }

    /** 日志用：合并空白 + 截断，避免一行日志占十几行 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        // ★ 必须 replaceAll：replace 是按【字面量】替换，"\\s+" 这五个字符根本不存在于文本里
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...(截断)";
    }

    /** 循环继续：<b>保留</b> proposedTool，让条件边读到它 */
    private Map<String, Object> finishContinue(AiMessage message) {
        return Map.of(
                MultiAgentState.MESSAGES, message,
                // ★ 重置 approvalDecided：表示的是"当前这个提议已被回答"，
                //   不是"这个人曾经回答过"。不重置 → 第二次危险操作绕过审批直接执行。
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    /** 本轮结束：写 NO_TOOL，条件边据此回到 supervisor 收尾 */
    private Map<String, Object> finishDone(AiMessage message) {
        return Map.of(
                MultiAgentState.MESSAGES, message,
                MultiAgentState.PROPOSED_TOOL, NO_TOOL,
                MultiAgentState.APPROVAL_DECIDED, false
        );
    }

    private static boolean isNoTool(String tool) {
        return tool == null || tool.isBlank() || NO_TOOL.equals(tool);
    }
}
```

> **改动点（相对现在）**：`apply` / `interrupt` 去掉了 `throws Exception`（不再需要）；其他逻辑**一行没动**。
> 原来 `apply` 上挂着 `throws Exception` 但没有实际抛受检异常。

---

### 3.14 `config/GraphProperties.java`

```java
package com.best.ai_graph.config;

import com.best.ai_common.mcp.ToolRisk;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体图的配置项。
 *
 * <p><b>前缀是 {@code ai.graph}</b> —— 它属于框架，不属于某个应用。
 *
 * <p><b>为什么用属性类而不是散落的 {@code @Value}</b>：
 * ① 默认值写在字段上（少一个"服务没配就崩"的可能）；
 * ② 一眼能看全"这张图有哪些可调项"；
 * ③ 从 yml 到对象的路径是显式的，不依赖字符串 key。
 */
@Data
@ConfigurationProperties(prefix = "ai.graph")
public class GraphProperties {

    /**
     * 这张图的标识。
     *
     * <p><b>它是双重标识，重复会静默串号</b>：
     * ① checkpoint 的 key 命名空间；② 长期记忆表的 {@code source_assistant}。
     *
     * <p>留空则取 {@code spring.application.name} —— 所以新建服务时
     * <b>只要服务名唯一，这项就可以不配</b>。
     *
     * <p><b>两种串号的严重程度不一样：</b>
     * checkpoint 串了可以清 Redis 恢复；长期记忆的 {@code source_assistant} 串了
     * <b>不可逆</b> —— 脏数据和正常数据长得一样，事后无法区分归属。
     */
    private String assistantId;

    /**
     * 达到或超过这个等级的工具，需要人工批准后才执行。
     *
     * <p><b>刻意声明成 String 而不是 {@link ToolRisk}。</b>
     * 用枚举类型的话，配置绑定会走 {@code valueOf} —— 写错一个字母就抛异常、服务起不来。
     * 而这里最需要的行为是 {@link ToolRisk#parse} 的：<b>不认识的值按 HIGH 处理</b>
     * （即"更严格"，不是"更宽松"）。
     */
    private String approvalMinRisk = "high";

    /** checkpoint 的存活时间（小时）。会话状态不是资产，配 TTL 避免无限堆积 */
    private long checkpointTtlHours = 24;

    /** 解析成枚举。任何不认识的值都会变成 HIGH 并打一条 warn */
    public ToolRisk approvalMinRisk() {
        return ToolRisk.parse(approvalMinRisk);
    }
}
```

---

### 3.15 `config/AiGraphAutoConfiguration.java`

```java
package com.best.ai_graph.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.graph.MultiAgentGraphFactory;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@code ai-graph} 的装配。
 *
 * <p><b>为什么必须走自动装配，而不是 {@code @Component} + 组件扫描。</b>
 * 业务模块的 {@code @SpringBootApplication} 只扫描自己的包（{@code com.best.multiagent}），
 * <b>扫不到 {@code com.best.ai_graph}</b>。走
 * {@code META-INF/spring/...AutoConfiguration.imports} 才能让任何依赖它的服务直接拿到 Bean。
 * （和 {@code ai-common} / {@code ai-memory} 的做法一致。）
 *
 * <p><b>依赖要求（用之前必须满足）</b>：需要 DataSource（{@code McpToolCatalog} 依赖它）、
 * Redis（checkpoint）、以及 {@code ai.mcp.enabled=true}。
 * <b>"图建不出来" 应该表现为启动失败，而不是运行期悄悄不工作</b> ——
 * 所以这里不加降级分支。
 */
@AutoConfiguration
@EnableConfigurationProperties(GraphProperties.class)
public class AiGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MultiAgentGraphFactory multiAgentGraphFactory(
            ChatModelRegister chatModelRegister,
            McpToolCatalog mcpToolCatalog,
            McpClientRegister mcpClientRegister,
            MemoryRecallService memoryRecallService,
            UserMemoryRecorder userMemoryRecorder,
            RedisProperties redisProperties,
            GraphProperties graphProperties,
            @Value("${spring.application.name:multiagent}") String applicationName) {
        return new MultiAgentGraphFactory(
                chatModelRegister,
                mcpToolCatalog,
                mcpClientRegister,
                memoryRecallService,
                userMemoryRecorder,
                redisProperties,
                graphProperties,
                applicationName);
    }
}
```

---

### 3.16 `graph/MultiAgentGraphFactory.java` ★ 核心

```java
package com.best.ai_graph.graph;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.agent.ExpertAgent;
import com.best.ai_graph.config.GraphProperties;
import com.best.ai_graph.node.*;
import com.best.ai_graph.state.MultiAgentState;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 建图工厂 —— <b>三个参数建出一整张多智能体图</b>。
 *
 * <p>它建的图<b>形状是固定的</b>：
 * <pre>
 *   START → recallMemory → supervisor ⇄ [propose 专家 × N] ⇄ toolExecute
 *                              │
 *                              └──(结束)──→ writeMemory → END
 * </pre>
 *
 * <p>它变的是"<b>有几个专家</b>" —— 由传入的路由枚举决定。加一个 expert 只加枚举一行，
 * 节点、边、路由表全部自动生成，<b>不存在"漏改一处"的可能</b>。
 */
@Slf4j
public class MultiAgentGraphFactory {

    /**
     * 工具执行完之后"结束循环、回调度器"的边 key。
     *
     * <p>刻意不复用 {@code FINISH.edgeKey()}：{@code FINISH} 的含义是"整个任务结束"，
     * 这里是"本轮 ReAct 结束、回去看全局" —— 两件事借用同一个名字是将来最容易读错的地方。
     */
    private static final String LOOP_END = "loopEnd";

    /** 没有提议时专家写的标记。和 ExpertNode / ToolExecuteNode 里的约定一致 */
    private static final String NO_TOOL = "NONE";

    private final ChatModelRegister chatModelRegister;
    private final McpToolCatalog toolCatalog;
    private final McpClientRegister mcpRegister;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder memoryRecorder;
    private final RedisProperties redisProperties;
    private final GraphProperties properties;
    private final String applicationName;

    public MultiAgentGraphFactory(ChatModelRegister chatModelRegister,
                                  McpToolCatalog toolCatalog,
                                  McpClientRegister mcpRegister,
                                  MemoryRecallService recallService,
                                  UserMemoryRecorder memoryRecorder,
                                  RedisProperties redisProperties,
                                  GraphProperties properties,
                                  String applicationName) {
        this.chatModelRegister = chatModelRegister;
        this.toolCatalog = toolCatalog;
        this.mcpRegister = mcpRegister;
        this.recallService = recallService;
        this.memoryRecorder = memoryRecorder;
        this.redisProperties = redisProperties;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    /**
     * 建一张多智能体图。
     *
     * @param routingEnum     路由枚举。<b>每个值要么是一个专家，要么是"结束"标记</b>
     *                        （靠 {@link ExpertRouting#expertRole()} 是否为 null 区分）。
     *                        <b>至少要有一个"结束"标记</b>，否则 supervisor 永远出不去
     * @param decider         调度决策。<b>业务侧用一行 lambda 把调度器接上</b> ——
     *                        框架因此不需要认识任何具体枚举
     * @param fallbackEdgeKey 调度失败时的兜底路由目标。必须是一个真实存在的 key
     */
    public <E extends Enum<E> & ExpertRouting> CompiledGraph<MultiAgentState> createAgent(
            Class<E> routingEnum,
            RouteDecider decider,
            String fallbackEdgeKey) throws GraphStateException {

        // 专家 Agent 全平台一个实现：只有 .chatModel(...)，【没有 .tools(...)】。
        // 挂了 .tools(...) 之后 LangChain4j 会在专家节点内部当场执行工具，
        // 而审批是跨 HTTP 请求的异步动作 —— "工具已经跑了才问要不要批准"是没有意义的。
        ExpertAgent expertAgent = AiServices.builder(ExpertAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();

        String assistantId = resolveAssistantId();
        RedisSaver saver = buildRedisSaver(assistantId);

        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                // ---- 固定部分：四个单例节点，【总是建】 ----
                // 刻意不加"要不要记忆 / 要不要工具"的开关：一旦开始加开关，
                // 就走上"配置项膨胀 → 每个组合都有人要 → 最后需要逃生舱"那条路了。
                .addNode(GraphNodes.RECALL_MEMORY, node(new RecallMemoryNode(recallService)))
                .addNode(GraphNodes.SUPERVISOR, node(new SupervisorNode(decider, fallbackEdgeKey)))
                .addNode(GraphNodes.TOOL_EXECUTE,
                        node(new ToolExecuteNode(mcpRegister, toolCatalog, properties.approvalMinRisk())))
                .addNode(GraphNodes.WRITE_MEMORY, node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, GraphNodes.RECALL_MEMORY)
                .addEdge(GraphNodes.RECALL_MEMORY, GraphNodes.SUPERVISOR)
                .addEdge(GraphNodes.WRITE_MEMORY, GraphDefinition.END);

        // ---- 由枚举驱动的部分：专家节点 + 两张路由表 ----
        Map<String, String> supervisorRoutes = new HashMap<>();
        Map<String, String> toolRoutes = new HashMap<>();
        int expertCount = 0;
        boolean hasEndMarker = false;

        for (E routing : routingEnum.getEnumConstants()) {
            String key = routing.edgeKey();
            String role = routing.expertRole();

            if (role == null) {
                // 不是专家 —— 是"结束"标记。不建节点，只给 supervisor 建一条"指向收尾"的边
                supervisorRoutes.put(key, GraphNodes.WRITE_MEMORY);
                hasEndMarker = true;
                continue;
            }

            // ★ 这里不需要校验提示词占位符：
            //   {{tools}} / {{memory}} 住在 ExpertAgent 的 @SystemMessage 注解里 ——
            //   那是框架自己的代码，漏不了。
            graph.addNode(key, node(new ExpertNode(routing, expertAgent, toolCatalog)));
            graph.addEdge(key, GraphNodes.TOOL_EXECUTE);
            // 同一个 key 出现在两张表里：supervisor 派发给它、循环结束时回到它
            supervisorRoutes.put(key, key);
            toolRoutes.put(key, key);
            expertCount++;
        }

        // ---- 建图期校验：三条，全部是"早失败好过跑起来才发现" ----
        if (expertCount == 0) {
            throw new IllegalArgumentException("路由枚举 " + routingEnum.getSimpleName()
                    + " 里没有任何专家（expertRole() 全为 null）");
        }
        if (!hasEndMarker) {
            throw new IllegalArgumentException("路由枚举 " + routingEnum.getSimpleName()
                    + " 里没有'结束'标记（expertRole() 为 null 的值）—— supervisor 将永远无法收尾，"
                    + "图会一直转到 recursionLimit");
        }
        // ★ 兜底 key 必须是真实存在的路由目标。
        //   不校验的话，调度失败时 next 写的是一个 Map 里没有的 key ——
        //   条件边找不到映射，症状是"偶发路由失败"，而这条路径平时根本不会走到。
        if (!supervisorRoutes.containsKey(fallbackEdgeKey)) {
            throw new IllegalArgumentException("fallbackEdgeKey=[" + fallbackEdgeKey
                    + "] 不在 " + routingEnum.getSimpleName() + " 的路由表里。可用: "
                    + supervisorRoutes.keySet());
        }

        // 循环结束 → 回调度器
        toolRoutes.put(LOOP_END, GraphNodes.SUPERVISOR);

        log.info("建图完成: assistantId={} 专家数={} 审批阈值={}",
                assistantId, expertCount, properties.approvalMinRisk());

        return graph
                .addConditionalEdges(GraphNodes.SUPERVISOR,
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        supervisorRoutes)
                .addConditionalEdges(GraphNodes.TOOL_EXECUTE,
                        AsyncEdgeAction.edge_async(MultiAgentGraphFactory::routeAfterTool),
                        toolRoutes)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .recursionLimit(25)
                        .build());
    }

    /**
     * 工具执行完之后该去哪。
     *
     * <p><b>这是扁平化 ReAct 的心脏</b>：它把"循环"从 Java 的 for 语句挪到了图上的一条条件边 ——
     * 于是每一步都是真实的图节点，<b>既能被 checkpoint，也能被 {@code InterruptibleAction} 拦住</b>。
     *
     * <p>（用 Java for 循环写不行：{@code interrupt()} 是<b>节点级</b>的，
     * 节点内部的循环它看不见 —— 审批最多只能拦住第一次。）
     *
     * @return {@code LOOP_END} 表示结束循环回调度器；否则返回专家名（= 回那个专家的 propose 节点）
     */
    private static String routeAfterTool(MultiAgentState state) {
        String tool = state.proposedTool();

        // 模型说"不需要再调工具了" —— 本轮 ReAct 结束
        if (tool == null || tool.isBlank() || NO_TOOL.equals(tool)) {
            return LOOP_END;
        }

        String by = state.proposedBy();
        if (by == null || by.isBlank()) {
            // 边界：提议非空但来源缺失（正常流程不会出现）。
            // 不能瞎猜一个专家 —— 回调度器是唯一安全的选择：
            // 最坏是"少循环一轮、由 supervisor 重新派发"，而不是"跑到一个错的专家那里执行"
            log.warn("proposedTool={} 非空但 proposedBy 为空，无法确定回哪个专家，改为回调度器", tool);
            return LOOP_END;
        }
        return by;
    }

    /** 没配 {@code ai.graph.assistant-id} 就用服务名 —— 只要服务名唯一，这一项就可以不配 */
    private String resolveAssistantId() {
        String configured = properties.getAssistantId();
        return (configured == null || configured.isBlank()) ? applicationName : configured;
    }

    private RedisSaver buildRedisSaver(String namespace) {
        var builder = RedisSaver.builder()
                .host(redisProperties.getHost())
                .port(redisProperties.getPort())
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
                // 加服务命名空间 —— 不加的话多个图应用共用 Redis 会串号（静默的）
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(namespace))
                .ttl(properties.getCheckpointTtlHours(), TimeUnit.HOURS);
        String password = redisProperties.getPassword();
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return builder.build();
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action) {
        // ★ 这一行是"可中断性"能生效的关键：
        //   node_async 内部会 instanceof 检查 InterruptibleAction，
        //   是的话用动态代理再造一个同时实现两个接口的对象。
        //   ToolExecuteNode 同时实现了这两个接口 → 走的就是这条路径。
        return AsyncNodeAction.node_async(action);
    }
}
```

> **相对 36 号的三个改动**：① 构造器多了 `String applicationName`（从 `@Value` 变成普通参数，因为注入点搬到了自动装配类）；② `@Component` 去掉（改成自动装配类里的 `@Bean`）；③ `createAgent` 从 2 参数变 3 个 + 三条建图期校验。

---

## 4. `ai-multiagent` 瘦身后的代码（6 个文件）

### 4.1 要**删除**的文件

```
ai-multiagent/src/main/java/com/best/multiagent/state/MultiAgentState.java
ai-multiagent/src/main/java/com/best/multiagent/graph/GraphNodes.java
ai-multiagent/src/main/java/com/best/multiagent/graph/GraphThreadIds.java
ai-multiagent/src/main/java/com/best/multiagent/graph/NamespacedKeyNamingStrategy.java
ai-multiagent/src/main/java/com/best/multiagent/node/SupervisorNode.java
ai-multiagent/src/main/java/com/best/multiagent/node/ExpertNode.java
ai-multiagent/src/main/java/com/best/multiagent/node/RecallMemoryNode.java
ai-multiagent/src/main/java/com/best/multiagent/node/WriteMemoryNode.java
ai-multiagent/src/main/java/com/best/multiagent/node/ToolExecuteNode.java
ai-multiagent/src/main/java/com/best/multiagent/agent/ExpertAgent.java
```

**删完之后 `graph/` 和 `state/` 两个包会空掉 —— 整个目录一并删掉。**
`node/` 包也整个删掉（5 个全搬走了）。

### 4.2 `agent/SupervisorAgent.java`（改：枚举实现接口 + 持有 Expert）

```java
package com.best.multiagent.agent;

import com.best.ai_graph.expert.Expert;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.multiagent.expert.AnalysisExpert;
import com.best.multiagent.expert.KnowledgeExpert;
import com.best.multiagent.expert.OrderExpert;
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
     * （LangChain4j 的 {@code EnumOutputParser.formatInstructions()} 会把它拼进提示词），
     * 所以写得越具体越好。
     */
    enum Routing implements ExpertRouting {

        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER("订单助手", new OrderExpert()),

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS("数据分析助手", new AnalysisExpert()),

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE("知识库助手", new KnowledgeExpert()),

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH(null, null);                    // ★ 两个都是 null = 不是专家，只是路由终点

        private final String role;
        private final Expert expert;

        Routing(String role, Expert expert) {
            this.role = role;
            this.expert = expert;
        }

        /**
         * 边 key、节点名、MCP server key —— 三者同一个值。
         *
         * <p>统一规则 {@code name().toLowerCase()}：只要改了枚举名，三处一起变，
         * <b>不可能再对不上</b>。
         */
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

> **注意 import 有 3 个来自 `com.best.multiagent.expert`**（三个准则实现），
> 另两个来自 `com.best.ai_graph`（`Expert` 接口 + `ExpertRouting` 接口）。
> **这是整个文件里唯一"同时认识框架和业务"的地方** —— 它是接缝，它本来就该这样。

### 4.3 `expert/OrderExpert.java`（新建）

```java
package com.best.multiagent.expert;

import com.best.ai_graph.expert.Expert;

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

### 4.4 `expert/AnalysisExpert.java`（新建）

```java
package com.best.multiagent.expert;

import com.best.ai_graph.expert.Expert;

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

### 4.5 `expert/KnowledgeExpert.java`（新建）

```java
package com.best.multiagent.expert;

import com.best.ai_graph.expert.Expert;

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

### 4.6 `config/MultiAgentGraphConfig.java`（改：只剩两个 Bean）

```java
package com.best.multiagent.config;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.graph.MultiAgentGraphFactory;
import com.best.ai_graph.state.MultiAgentState;
import com.best.multiagent.agent.SupervisorAgent;
import dev.langchain4j.service.AiServices;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多智能体图的装配。
 *
 * <p><b>整个类只有两个 Bean</b> —— 建图的全部复杂性都在 {@link MultiAgentGraphFactory} 里。
 * 想改图的形状就去改工厂；这里只负责"把业务契约接上"。
 *
 * <p><b>这里就是"业务与框架的接缝"</b>：一个调度器 + 一行建图调用。
 * 下面那行 lambda 是<b>整个模块里唯一一处把业务枚举和框架接起来的地方</b>。
 */
@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    /** 这个图的调度器。它的 {@code Routing} 枚举就是这张图的业务划分 */
    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    /**
     * ★ 一行建图。
     *
     * <p>三个参数分别是：
     * <ol>
     *   <li><b>路由枚举</b> —— 这个图有哪几个专家</li>
     *   <li><b>调度决策</b> —— 怎么问"下一步交给谁"。lambda 里那一下
     *       {@code .edgeKey()} 是必需的：框架只认 {@code ExpertRouting} 接口，
     *       不认具体枚举</li>
     *   <li><b>兜底路由</b> —— 调度失败去哪。用 {@code FINISH} 的 key，
     *       语义是"问不出来就直接收尾"</li>
     * </ol>
     */
    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            MultiAgentGraphFactory graphFactory) throws GraphStateException {
        return graphFactory.createAgent(
                SupervisorAgent.Routing.class,
                messages -> supervisorAgent.route(ChatMessageTexts.render(messages)),
                SupervisorAgent.Routing.FINISH.edgeKey());
    }
}
```

> **注意 lambda 返回的是 `Routing`，而 `RouteDecider.decide` 要求返回 `ExpertRouting`** ——
> 因为 `Routing implements ExpertRouting`，子类型自动兼容。<b>这就是解绑能成立的全部原因：一行 lambda，零反射。</b>

### 4.7 `controller/AgentController.java`（只改 import）

```java
package com.best.multiagent.controller;

import com.best.ai_graph.graph.GraphNodes;          // ← 包名变
import com.best.ai_graph.graph.GraphThreadIds;      // ← 包名变
import com.best.ai_graph.state.MultiAgentState;     // ← 包名变
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphInterruptException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@Slf4j
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final CompiledGraph<MultiAgentState> graph;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> input = new HashMap<>();
        input.put(MultiAgentState.MESSAGES, List.of(UserMessage.from(req.message())));
        input.put(MultiAgentState.USER_ID, userId == null ? "" : userId);
        input.put(MultiAgentState.SESSION_ID, req.sessionId);
        input.put(MultiAgentState.PROPOSED_TOOL, "");
        input.put(MultiAgentState.APPROVED, false);
        // ★ 每轮新请求都从 false 开始。这是 approvalDecided 的【第二处】重置 ——
        //   只靠 ToolExecuteNode 里的那处不够：万一有路径没走到 finishXxx()，
        //   脏标记会跨请求残留，症状是"这一次的危险操作没停下问人"。
        input.put(MultiAgentState.APPROVAL_DECIDED, false);
        return run(() -> graph.invoke(GraphInput.args(input), config), threadId, config);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody ApproveRequest req) {
        String threadId = GraphThreadIds.of(req.sessionId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        log.info("收到审批: threadId={} approved={}", threadId, req.approved());
        return run(() -> graph.invoke(GraphInput.resume(Map.of(
                MultiAgentState.APPROVED, req.approved(),
                // ★ 这一处必须带上：不带的话 interrupt() 会【再次中断】，图卡死
                MultiAgentState.APPROVAL_DECIDED, true)),
                config),
                threadId, config);
    }

    private Map<String, Object> run(GraphRunner runner, String threadId, RunnableConfig config) {
        try {
            Optional<MultiAgentState> result = runner.run();
            if (result.isEmpty()) {
                return Map.of("status", "error", "message", "图没有返回状态");
            }
            return respond(result.get(), threadId, config);
        } catch (Exception e) {
            if (GraphInterruptException.of(e).isPresent()) {
                return waiting(threadId, config);
            }
            log.error("图执行失败 threadId={}", threadId, e);
            return Map.of("status", "error", "threadId", threadId, "message", e.getMessage());
        }
    }

    private Map<String, Object> respond(MultiAgentState state, String threadId, RunnableConfig config) {
        if (GraphNodes.TOOL_EXECUTE.equals(nextNodeOf(config))) {
            return waiting(threadId, config);
        }
        return Map.of(
                "status", "done",
                "threadId", threadId,
                "answer", lastAiText(state.messages())
        );
    }

    private Map<String, Object> waiting(String threadId, RunnableConfig config) {
        StateSnapshot<MultiAgentState> snapshot = graph.getState(config);
        MultiAgentState state = snapshot == null ? null : snapshot.state();
        if (state == null) {
            return Map.of("status", "waiting_approval", "threadId", threadId);
        }
        return Map.of(
                "status", "waiting_approval",
                "threadId", threadId,
                "proposedTool", state.proposedTool(),
                "proposedArgs", state.proposedArgs()
        );
    }

    private String nextNodeOf(RunnableConfig config) {
        StateSnapshot<MultiAgentState> snapshot = graph.getState(config);
        if (snapshot == null) {
            return "";
        }
        String next = snapshot.nextNodeId();
        return next == null ? "" : next;
    }

    private String lastAiText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage am) {
                String t = am.text();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface GraphRunner {
        Optional<MultiAgentState> run() throws Exception;
    }

    public record ChatRequest(String message, String sessionId) {
    }

    public record ApproveRequest(String sessionId, boolean approved) {
    }
}
```

### 4.8 `MultiAgentApplication.java`（不变）

```java
package com.best.multiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MultiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MultiAgentApplication.class, args);
    }
}
```

> **不需要加 `@ComponentScan("com.best.ai_graph")`** —— 自动装配走的是 classpath 上的
> `AutoConfiguration.imports`，不经过组件扫描。**这正是选自动装配而不是 `@Component` 的原因。**

---

## 5. `application.yml` 改动

**只有一处**：`ai.multiagent:` → `ai.graph:`。

```yaml
ai:
  graph:                                  # ← 原来是 ai.multiagent
    # assistant-id 可以不配 —— 缺省取 spring.application.name。
    # ★ 但它是【双重标识】（checkpoint 命名空间 + 长期记忆 source_assistant），
    #   重复会静默串号，所以服务名一定要唯一。
    # approval-min-risk 也可以不配，默认 high
    checkpoint-ttl-hours: 24

  mcp:                                    # ← 以下全部不动
    enabled: true
    tool-timeout: 60s
    risk-cache-ttl: 60s
    servers:
      order:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_ORDER}"
        timeout: 30s
      analysis:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_ANALYSIS}"
        timeout: 30s
      knowledge:
        transport: http
        url: http://127.0.0.1:8800/mcp/
        headers:
          Authorization: "Bearer ${MCP_TOKEN_KNOWLEDGE}"
        timeout: 30s
  # chat / embed / memory 三段完全不动
```

> ⚠️ **`ai.mcp.servers` 的三个 key（`order` / `analysis` / `knowledge`）
> 必须和 `Routing` 枚举的 `edgeKey()` 一致** —— 它们就是靠这个关联的。
> 我上面那三行 token 是你已经填好的明文，**抽模块不动它**（要不要改成环境变量是另一件事）。

---

## 6. 验收

### 6.1 编译 + 启动

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

**先只编 `ai-graph`**（更快定位问题）：

```bash
/opt/homebrew/bin/mvn -o -DskipTests -pl ai-graph -am clean compile
```

启动日志应该有：

```
建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH
```

### 6.2 四个用例 + 硬指标（一个都不能少）

```bash
# ① 低危工具 → directly 执行
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'
# 期待 {"status":"done"}，日志有"未达审批阈值，直接执行"

# ② 高危工具 → 必须停下，且工具【没有】被执行
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'
# 期待 {"status":"waiting_approval","proposedTool":"mysql__mysql_query",...}

# ③ 批准 → 工具执行，且【只执行一次】
curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
grep -c "工具执行成功" <日志>          # 必须恰好 1

# ④ 连续两次高危 → 第二次照样必须停
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"再统计一遍","sessionId":"r2"}'
# ★ 仍期待 waiting_approval。返回 done —— approvalDecided 没重置

# 硬指标
grep -c "调度决定" <日志>              # ★ 必须 ≤ 2（派发 + 收尾），与工具轮数无关
```

### 6.3 ★ 本单特有：验证"模块边界真的立住了"

```bash
# 这个命令必须返回 0 行 —— 框架侧不许认识任何业务类
grep -rn "com\.best\.multiagent" ai-graph/src/ ; echo "命中行数: $(grep -rn 'com\.best\.multiagent' ai-graph/src/ | wc -l)"
```

**同理反查业务侧**——业务模块**允许** import `com.best.ai_graph.*`（这是它存在的意义）：

```bash
grep -rn "com\.best\.ai_graph" ai-multiagent/src/ | wc -l    # 应该是 10 左右，不是 0
```

### 6.4 ★ 验证"加一个 expert 的成本真的降到 2 处"

按 36 号第 3 节加 `LOGISTICS("物流助手", new LogisticsExpert())` ——

- 新建 `expert/LogisticsExpert.java`（只写准则）
- 枚举加一行
- yml 加一个 token

重启 → 日志 `专家数=4`。**验完删掉。**

### 6.5 三条建图期校验，各验一次

这三条是**故意加的 fail-fast**，值得各跑一次确认它们真的会拦：

| 造什么错 | 期望 |
|---|---|
| 枚举里删掉 `FINISH` | 启动失败：`没有'结束'标记` |
| 枚举里三个专家全删，只剩 `FINISH` | 启动失败：`没有任何专家` |
| 第三个参数传 `"finished"`（打错） | 启动失败：`不在 ... 的路由表里。可用: [order, analysis, knowledge, finish]` |

> **第三条最重要** —— 它防的是"调度失败时 next 写了一个不存在的 key"。
> 那条路径**平时根本不会走到**，所以不校验的话，它会在某次模型抽风时才暴露，
> 而且表现为"偶发路由失败"，极难复现。

---

## 7. 自查清单

**编译期**
- [ ] 父 `pom.xml` 的 `<modules>` 里有 `ai-graph`，且在 `ai-memory` **之后**
- [ ] `ai-graph/pom.xml` **没有** `spring-boot-maven-plugin`
- [ ] `AutoConfiguration.imports` 文件名一字不差，内容是 `com.best.ai_graph.config.AiGraphAutoConfiguration`
- [ ] `ai-multiagent/pom.xml` 里 `ai-memory` 已换成 `ai-graph`
- [ ] `grep -rn "com.best.multiagent" ai-graph/src/` 返回 **0 行**

**删除**
- [ ] `ai-multiagent` 下 10 个文件已删（`graph/` · `state/` · `node/` 三个包整个删掉）
- [ ] `agent/` 包下只剩 `SupervisorAgent.java`

**行为**
- [ ] 启动日志 `专家数=3`
- [ ] 6.2 四条用例全过 + `调度决定 ≤ 2`
- [ ] 6.3 的两条 grep 符合预期
- [ ] 6.5 三条校验各拦一次

---

## 8. 本单**不做**什么

| 不做 | 理由 |
|---|---|
| 把 `AgentController` 也搬进 `ai-graph` | HTTP 契约是**业务选择**（响应字段叫什么、要不要鉴权头）。搬进去会让框架被迫依赖 web，而且每个应用想改响应格式都要动公共模块 |
| 泛型化 `SupervisorAgent.route()` 的返回类型 | 会让 LangChain4j 的 `EnumOutputParser` 拿不到枚举值（泛型擦除后返回裸 `Enum`）。**每个图必须有自己的 `SupervisorAgent` 接口** —— 这恰恰是 `RouteDecider` 存在的理由 |
| 给 `createAgent` 加"要不要记忆 / 要不要工具"的开关 | **"总是建"是刻意选择**。一旦开始加开关，就走上"配置组合爆炸 → 最后需要逃生舱"的路 |
| 抽 `UserMemoryFacade`（36 号第 8 节那件事） | **和本单无关，且现在不该做** —— 它要动 `ai-memory`。**一次只动一个模块边界** |
| 让 `MultiAgentState` 支持业务自定义字段 | 现在的 10 个字段是"这张图的形状"。真需要加字段的图，**说明它不该套这个骨架** |

---

## 9. 这次抽取改变了什么、没改变什么

**改变了：**

| | 抽之前 | 抽之后 |
|---|---|---|
| 第二个图应用怎么开始 | 复制 `ai-multiagent`（连 10 个框架文件一起复制） | **依赖 `ai-graph`，只写自己的 4 个文件** |
| 框架件改一处 | 要改 N 份副本 | **改一处，N 个应用同时生效** |
| `ai-multiagent` 里有什么 | 14 个文件，9 个和这个业务无关 | **6 个文件，每个都是"这个助手特有的"** |

**没改变**（这才是重点）：

> 今天踩过的坑，**一条都没有因为抽模块而消失**。
> `approvalDecided` 要重置、拒绝分支要和 `approved` 做与、
> `proposedTool` 不能被清空、`finish` 的三条出口要统一、
> `InterruptibleAction` 带 i、`node_async` 要包一层、
> `调度决定 ≤ 2` ——
>
> **抽模块解决的是"同一个坑不要在第二个应用里再踩"，不是"第一个应用不会踩"。**
>
> 而那 13 条陷阱，**现在有了物理归属**：它们待的每一处代码，都只有一份。

---

## 10. 最后一个问题（留给你想）

`RouteDecider` 这个接口之所以成立，是因为它**只要求一件事**：
"给一串消息，告诉我去哪个节点"。

而 `SupervisorNode` 原来需要 `SupervisorAgent` 这个**具体接口**，其实它只用了其中一个方法。

> **如果一个类只用到另一个类的某一个方法，而那个方法的行为可以由调用方决定 ——
> 那么让它依赖"这个方法"（函数式接口）而不是"这个类"，绝大多数时候都是对的。**

反过来问一句：`ExpertNode` 为什么**不需要**同样的解绑？

提示：它依赖的 `ExpertRouting` 已经是接口了 —— 而且它用到了接口的**全部三个方法**。
**用到一个方法的，可以解绑；用到全部方法的，那个接口本身就是它的契约。**
