# 29 · multiagent 工具接入（第 1 步）：工具清单与执行

> **触发**：你要「给 expert 装不同的工具」+「按工具危险等级判断是否过审」
>
> **前置**：`28-ai-graph模块定案.md`（**已暂缓建模块**，5 个类留在 ai-multiagent）、`25-multiagent代码复核.md`
>
> **本轮已完成的对齐**（不再重议）：路线选「工具不挂 AiServices、由图节点执行」；
> risk 来源 = `mcp_gateway.tools` 表；expert 与工具的对应**由网关 token 决定**；
> expert 划分改成**按服务**；阈值可配；未知 risk 按 high 兜底。

---

## 0. 本轮范围 —— 分两步走，这一份只做第 1 步

| | **第 1 步（本单）** | 第 2 步（下一单） |
|---|---|---|
| 目标 | 工具**真的被调用** | 危险工具**停下等人批准** |
| 工具清单（MCP + risk） | ✅ | — |
| 模型输出结构化提议 | ✅ | — |
| `toolExecute` 节点执行工具 | ✅ | — |
| 按 risk 中断 | ❌ `interrupt()` 恒返回空 | ✅ 按 risk + 阈值判断 |
| `approvalDecided` 字段 | ✅ **先加进 SCHEMA**（第 2 步直接用） | 用它 |
| 怎么验证 | 日志里能看到工具真实执行结果 | 高危工具返回 `waiting_approval` |

**为什么拆成两步。** 第 1 步能独立验证「工具链路是通的」。
如果一次做完，万一停不下来，你分不清是**判断逻辑写错**还是**工具根本没接上** ——
这两件事的排查方向完全相反。

> ⚠️ **第 1 步的中间态要说清楚**：这一步**没有审批**（`interrupt()` 恒返回空），
> 高危工具 `mysql__mysql_query` 会被直接执行。
> **只在你自己的本地环境跑，别让真实用户碰。**

---

## 0.1 已核实的框架事实（`javap` 实测，别凭记忆改）

这一节的每一条都是从本机 jar 里读出来的。上一轮我用记忆写过 `MessageChannel`（根本不存在），
所以这次逐条验：

| 事实 | 结论 |
|---|---|
| 接口名 | **`org.bsc.langgraph4j.action.InterruptibleAction`（带 i）**。另有 `InterruptableAction`（不带 i）是**空壳标记接口**，无任何方法 |
| 方法签名 | `Optional<InterruptionMetadata<State>> interrupt(String nodeId, State state, RunnableConfig config)` |
| **调用时序** | `NodeHooks.applyActionWithHooksHandlingInterruption` 里 **`interrupt()` 先调、节点 `apply()` 后调** → **返回有值时节点根本不执行** |
| 怎么挂到节点 | `addNode` **没有**收 `InterruptibleAction` 的重载。靠 `AsyncNodeAction.node_async()` 内部 `instanceof` 检查 + 动态代理自动带上 → 节点类**同时实现 `NodeAction` + `InterruptibleAction`** 即可 |
| 构造中断元数据 | `InterruptionMetadata.builder(nodeId, state).reason(String).build()` |
| LangChain4j 的工具前置钩子 | `beforeToolExecution(Consumer<BeforeToolExecution>)` —— **`Consumer` 无返回值、`BeforeToolExecution` 只有读方法 → 只能看，不能否决** |
| LangChain4j 的工具事件 | 只有 `ToolExecutedEvent`（已执行）/ `ToolCompensatedEvent`（补偿）—— **没有"将执行"事件** |
| MCP 客户端 | `McpClient.listTools()` → `List<ToolSpecification>`；`McpClient.executeTool(ToolExecutionRequest)` → `ToolExecutionResult` |
| 工具调用请求 | `ToolExecutionRequest.builder().id(String).name(String).arguments(String).build()` |
| 工具调用结果 | `ToolExecutionResult.resultText()` / `.isError()` |

**上面第 3 条和第 6 条合起来是本方案的全部依据**：
LangChain4j 没有"执行前否决"的能力，而 langgraph4j 有 —— 所以**工具必须由图节点执行**。

---

## 1. 数据从哪来

| 需要什么 | 来源 | 关键字段 |
|---|---|---|
| 给模型看的**工具清单** | MCP `tools/list`（`McpClient.listTools()`） | `name` / `description` / `parameters` |
| 给守卫看的**危险等级** | `mcp_gateway.tools` 表 | `effective_name` → `risk` |
| **执行** | MCP `tools/call`（`McpClient.executeTool()`） | — |

**关联键 = 工具名。** 网关侧 `AppTool` 的构造是 `name=descriptor.effective_name`，
所以 **MCP 返回的 `name` 就等于表里的 `effective_name`**。

**当前库里的实际数据**（我连库查过）：

| id | 服务 | `effective_name` | risk |
|---|---|---|---|
| 7 | 订单mcp（`order`） | `order__searchMultipartBizOrder` | low |
| 8 | 知识库mcp（`outline`） | `outline__list_documents` | low |
| 9 | 知识库mcp（`outline`） | `outline__fetch` | low |
| 6 | 数据分析mcp（`mysql`） | `mysql__mysql_query` | **high** |

> ⚠️ `mysql__mysql_query` 的 description 里写着 `(READ-ONLY)`，但 risk 是 `high`。
> **说明 risk 是管理员维护的静态判断，不能靠工具的自我描述去猜。** 必须读表。

### 1.1 「expert 用什么工具」为什么不用本项目配置

网关有**三级权限**（实测表结构）：

| 级别 | 表 | 粒度 |
|---|---|---|
| 1 | `token_services` | token → 能访问哪些**上游服务** |
| 2 | `token_tools` | token → 能访问哪些**具体工具** |
| 3 | `tokens.allow_high_risk` | 能否调用**高风险工具** |

所以「给 expert 装不同工具」= **给每个 expert 配一个不同的 token**。
`McpClientRegister` 已经支持 `Map<String, Server>`，天然能表达多 token：

```yaml
ai.mcp.servers:
  order:      { ... token A ... }   # 只授权订单服务
  analysis:   { ... token B ... }   # 只授权 mysql 服务
  knowledge:  { ... token C ... }   # 只授权知识库服务
```

`register.get("order").listTools()` 返回的自然就是订单工具 ——
**本项目零维护映射，越权在网关层就被拦掉了。**

> ⚠️ 网关的 `allow_high_risk` **不替代你的审批**。网关源码注释原话：
> *"即便 token 开了 `allow_high_risk`、技术上调得动，下游仍应走 HITL"*。
> 所以 `ToolExecuteNode` 只认 `tools.risk`，不看 `allow_high_risk`。

---

## 2. 拓扑变化

**现在**（工具是假的，审批是无条件的）：

```
START → recallMemory → supervisor →{expertA|expertB}→ approvalGate → supervisor
                          ↑ 写死 plannedAction          ↑ interruptBefore 无条件停
```

**第 1 步之后**（工具是真的，审批暂时没有）：

```
START → recallMemory → supervisor →{expertOrder|expertAnalysis|expertKnowledge}→ toolExecute → supervisor
                                        ↑ 输出工具提议                          ↑ 直接执行
                                                                supervisor -(FINISH)-> writeMemory → END
```

**第 2 步之后**（只在 `toolExecute` 上加一个条件中断）：

```
                          ... → toolExecute → supervisor ...
                                   ↑ interrupt() 查 risk
                                   ↓ 高危 → 停下等批准（resume 后回到这个节点执行）
```

**关键**：第 1 步就把拓扑**定稿**。第 2 步只改 `interrupt()` 一个方法体，
不动图结构 —— 这样第 2 步的风险面极小。

---

## 3. 文件清单

| # | 动作 | 文件 |
|---|---|---|
| 1 | 新增 | `ai-common/.../mcp/ToolRisk.java` |
| 2 | 新增 | `ai-common/.../mcp/McpTool.java` |
| 3 | 新增 | `ai-common/.../mcp/db/McpToolEntity.java` |
| 4 | 新增 | `ai-common/.../mcp/db/McpToolMapper.java` |
| 5 | 新增 | `ai-common/.../mcp/McpToolCatalog.java` |
| 6 | 改造 | `ai-common/.../properties/McpProperties.java`（加一个缓存 TTL） |
| 7 | 改造 | `ai-common/.../config/AiCommonAutoConfiguration.java`（mapper 扫描 + catalog bean） |
| 8 | 改造 | `ai-multiagent/.../agent/SupervisorAgent.java`（Routing 按服务分） |
| 9 | 改造 | `ai-multiagent/.../agent/ExpertAgent.java`（输出结构化提议 + 修引号笔误） |
| 10 | 改造 | `ai-multiagent/.../node/ExpertNode.java` |
| 11 | 新增 | `ai-multiagent/.../node/ToolExecuteNode.java` |
| 12 | 改造 | `ai-multiagent/.../state/MultiAgentState.java` |
| 13 | 改造 | `ai-multiagent/.../config/MultiAgentGraphConfig.java` |
| 14 | 改造 | `ai-multiagent/.../controller/AgentController.java` |
| 15 | 改造 | `ai-multiagent/src/main/resources/application.yml` |
| 16 | 移除 | `ai-multiagent/.../node/ApprovalGateNode.java`（**从图里摘掉，文件保留**，第 2 步大改后回归） |

> `ApprovalGateNode`: **不要删文件** —— 第 2 步会把它改造成 `toolExecute` 的一部分（或者直接删掉）。
> 先注释掉 `addNode` 那行，保留文件，回来时好对照。

---

## 4. 代码

### 4.1 `ai-common/src/main/java/com/best/ai_common/mcp/ToolRisk.java`（新增）

```java
package com.best.ai_common.mcp;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具的危险等级 —— 直接对应 mcp_gateway 库 {@code tools.risk} 列的值域。
 *
 * <p><b>为什么"未知值按最严兜底"而不是抛异常。</b>
 * {@code risk} 在库里是 {@code varchar(8)}，当前实测只有 low / high，但网关的语义是三档。
 * 一旦管理员在网关侧加了新等级（比如 {@code critical}），两种做法都有害：
 * <ul>
 *   <li>抛异常 → 一个数据问题让整张图崩掉</li>
 *   <li>当成 LOW → <b>危险操作被静默放行</b>，这是数据泄露级别的故障</li>
 * </ul>
 * 所以选第三条：<b>不认识的一律按 HIGH</b>，并打 warn（每个陌生值只打一次，避免刷日志）。
 * 宁可多问一次人，不可偷偷执行。
 *
 * <p><b>为什么住 ai-common 而不是 ai-multiagent。</b>
 * "这个工具有多危险"不是某一张图的私有知识 —— assistant-demo 接工具时要用同一套判断。
 * 放在 ai-common 保证全平台对同一个工具名的危险判断一致；分散到各业务模块必然漂移。
 */
@Slf4j
public enum ToolRisk {

    LOW,
    MEDIUM,
    HIGH;

    /**
     * 已经警告过的原始值。
     * <p>{@code ConcurrentHashMap.newKeySet()} 而不是 {@code HashSet} ——
     * 这个静态集合会被多线程并发访问（多个请求同时在解析 risk）。
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * 把表里的原始字符串解析成枚举。
     *
     * <p><b>契约：永不抛异常、永不返回 null。</b>
     * 这里是"数据脏了"和"图崩了"之间的唯一屏障，所以它必须比调用方更宽容。
     *
     * @param raw {@code tools.risk} 的原始值。可能为 null、可能大小写不一、可能是没见过的值
     */
    public static ToolRisk parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return warnOnce("<空>", "risk 为空");
        }
        return switch (raw.trim().toLowerCase()) {
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high" -> HIGH;
            default -> warnOnce(raw, "未知 risk 值");
        };
    }

    private static ToolRisk warnOnce(String raw, String reason) {
        if (WARNED.add(raw)) {
            log.warn("{}: [{}] → 按最严等级 HIGH 处理。"
                    + "如果这是网关新增的等级，请在 ToolRisk.parse 里补映射", reason, raw);
        }
        return HIGH;
    }

    /** 等级顺序，供第 2 步的阈值比较用（{\@code >=} 判定"达到审批线"） */
    public boolean atLeast(ToolRisk threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
```

> **`atLeast` 现在就写**，因为第 2 步要用，而它属于"这个枚举本来就该有的能力"。
> 有了它，阈值判断就是 `risk.atLeast(threshold)` 一行，而不是散在各处的 if-else。
> 注意它依赖 `ordinal()` —— 所以**枚举常量的声明顺序是有意义的**（LOW < MEDIUM < HIGH），
> 谁将来往中间插值，编译器不会报错，但语义会变。注释里已经写明。

---

### 4.2 `ai-common/src/main/java/com/best/ai_common/mcp/McpTool.java`（新增）

```java
package com.best.ai_common.mcp;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * 一个"当前可用的工具"的完整视图 —— 把协议侧和网关侧两边的信息合到一起。
 *
 * <p><b>为什么要这个 record，不直接传 {@code ToolSpecification}。</b>
 * {@code ToolSpecification} 只有协议侧的信息（name / description / parameters），
 * <b>没有 risk</b>。而 risk 恰恰是本方案的核心判断依据。
 * 与其在每个使用点各查一次库，不如在造出来的那一刻就把两边合到一起，
 * 让"工具"这个概念在任何地方都是一个完整的东西。
 *
 * @param name       工具名。<b>等于 MCP tools/list 的 name，等于表里的 effective_name</b>
 * @param description 给模型看的说明
 * @param parameters 入参结构（拼进提示词，让模型知道怎么传参）
 * @param risk       危险等级。<b>表里查不到时是 HIGH</b>（见 {@link ToolRisk}）
 */
public record McpTool(
        String name,
        String description,
        JsonObjectSchema parameters,
        ToolRisk risk
) {
}
```

---

### 4.3 `ai-common/src/main/java/com/best/ai_common/mcp/db/McpToolEntity.java`（新增）

```java
package com.best.ai_common.mcp.db;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code mcp_gateway} 库的 {@code tools} 表。
 *
 * <p><b>只读。</b> 它的写入方是 MCP 网关（Python 侧），本平台永远不写。
 * 把它当只读表能在心态上避免一类问题：将来有人想"在本平台改 tool 的 risk"时，
 * 会先意识到那是双写、要先解决归属权。
 *
 * <p><b>为什么只映射用得到的列。</b> 表的完整列是：
 * {@code id / service_id / upstream_name / effective_name / description /
 * input_schema / schema_hash / risk / enabled / available / discovered_at / updated_at}。
 * 本平台只需要 {@code id / effective_name / risk / enabled / available / updated_at}。
 * 少映射一列就少一处"网关改了列类型我们跟着炸"的风险。
 *
 * <p><b>表名刻意不加库前缀。</b>
 * ai-multiagent 的 {@code spring.datasource.url} 已经指向 {@code mcp_gateway} 库，
 * 所以 {@code @TableName("tools")} 就够。写成 {@code "mcp_gateway.tools"} 的话，
 * 换环境（测试库 / 生产库）就全废了 —— 库名属于部署配置，不该出现在实体里。
 *
 * <p><b>{@code @TableField} 显式写列名</b>而不是靠下划线转驼峰：
 * 转驼峰依赖 {@code mybatis-plus.configuration.map-underscore-to-camel-case} 这个开关，
 * 而显式写死是零依赖的。<b>症状提醒</b>：如果哪天查询回来 {@code effectiveName} 是 null，
 * 就是这里被改错了。
 */
@Data
@TableName("tools")
public class McpToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外暴露的工具名。
     * <p><b>这是两张表之间的关联键</b> —— 等于 MCP {@code tools/list} 返回的 name。
     */
    @TableField("effective_name")
    private String effectiveName;

    /** 危险等级原始值：low / medium / high，也可能出现网关将来新增的值 */
    @TableField("risk")
    private String risk;

    /** 管理员开关：这个工具是否被启用 */
    @TableField("enabled")
    private Boolean enabled;

    /** 上游健康探测结果。{@code enabled && available} 才代表"真的能用" */
    @TableField("available")
    private Boolean available;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
```

---

### 4.4 `ai-common/src/main/java/com/best/ai_common/mcp/db/McpToolMapper.java`（新增）

```java
package com.best.ai_common.mcp.db;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code tools} 表的只读访问。
 *
 * <p><b>刻意不写 XML。</b> 本方案只需要"全表读一遍拿到 name→risk 映射"这一个操作，
 * {@code selectList(null)} 就能表达。手写 XML 只会多一个坑 ——
 * 项目里已经记过：改包名时 XML 的 {@code namespace} 不会自动跟着改，而且编译期不报错。
 */
public interface McpToolMapper extends BaseMapper<McpToolEntity> {
}
```

---

### 4.5 `ai-common/src/main/java/com/best/ai_common/mcp/McpToolCatalog.java`（新增）

```java
package com.best.ai_common.mcp;

import com.best.ai_common.mcp.db.McpToolEntity;
import com.best.ai_common.mcp.db.McpToolMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具目录 —— <b>把「MCP 协议里的工具」和「网关库里的危险等级」合成一个视图</b>。
 *
 * <p><b>它存在的理由。</b> 这两份信息在两个完全不同的地方：
 * 工具清单在网络另一端的 MCP 服务上（要握手、要按 token 过滤），
 * risk 在本地数据库里。任何需要使用工具的地方都得同时知道这两样，
 * 那就必然出现"有的人查了 risk、有的人忘了"。收进这里之后，
 * 调用方拿到的一定是完整的 {@link McpTool}。
 *
 * <p><b>为什么要缓存。</b>
 * <ul>
 *   <li>{@code listTools()} 是<b>一次网络调用</b>（MCP JSON-RPC），而它会被每个请求都用到</li>
 *   <li>risk 是本地查询（主键索引），但它在<b>判断中断时会被高频调用</b></li>
 * </ul>
 * 所以两边都带 TTL 缓存。TTL 做成可配（{@code ai.mcp.risk-cache-ttl}），
 * 值多长取决于"管理员改了 risk 之后，你能接受多久才生效"。
 *
 * <p><b>缓存失效的取舍写在明处</b>：网关那边改了 risk，本平台最多滞后一个 TTL。
 * 这是个<b>刻意的取舍</b>——因为它只是"要不要多问一次人"，滞后一个 TTL 的代价是
 * "某次操作多问了一次"，而不是"漏放行"。反过来如果不缓存，
 * 热点路径上每次判断都要读库。真要立刻生效，重启服务即可。
 */
@Slf4j
public class McpToolCatalog {

    private final McpClientRegister register;
    private final McpToolMapper toolMapper;
    private final Duration cacheTtl;

    /** 工具名 → 危险等级。空 Map 表示"还没加载过" */
    private volatile Map<String, ToolRisk> riskCache = Map.of();

    /** 上次加载时刻（毫秒）。volatile 保证可见性，不需要锁 —— 最坏情况多加载几次，无害 */
    private volatile long riskLoadedAt = 0L;

    public McpToolCatalog(McpClientRegister register, McpToolMapper toolMapper, Duration cacheTtl) {
        this.register = register;
        this.toolMapper = toolMapper;
        this.cacheTtl = cacheTtl;
    }

    /**
     * 某个 MCP 客户端（= 某个 expert 的 token）当前能看到的工具。
     *
     * <p><b>这里不做权限判断。</b> 能返回什么完全由网关按 token 决定 ——
     * 本平台再判一次就是第二份真相，两份真相必然对不上。
     *
     * @param clientKey {@code ai.mcp.servers} 里的 key，也就是 expert 的名字
     * @return 不可变列表。<b>任何失败都返回空列表，不抛异常</b> ——
     *         拉不到工具清单只该导致"这个专家这次没工具可用"，不该让整个请求失败
     */
    public List<McpTool> list(String clientKey) {
        McpClient client = register.get(clientKey);
        if (client == null) {
            log.warn("没有配置名为 {} 的 MCP 客户端，该专家本次无可用工具。"
                    + "检查 ai.mcp.servers 里有没有这个 key", clientKey);
            return List.of();
        }
        Map<String, ToolRisk> risks = riskSnapshot();
        try {
            return client.listTools().stream()
                    .map(spec -> toTool(spec, risks))
                    .toList();
        } catch (Exception e) {
            log.error("拉取工具清单失败 client={}，该专家本次无可用工具", clientKey, e);
            return List.of();
        }
    }

    private McpTool toTool(ToolSpecification spec, Map<String, ToolRisk> risks) {
        // 协议里有、但表里查不到 → 按 HIGH。
        // 这正是"未知按最严"的落点：宁可多问一次人，不可静默放行。
        ToolRisk risk = risks.getOrDefault(spec.name(), ToolRisk.HIGH);
        return new McpTool(spec.name(), spec.description(), spec.parameters(), risk);
    }

    /**
     * 读 risk 映射（带 TTL 缓存）。
     *
     * <p><b>为什么全表读而不是按 name 逐个查。</b> 当前表里只有 4 行，
     * 全表读一次的成本远低于"每个工具一次查询"。等工具数上千再改成按需查，
     * 那时的切换点就是这个方法内部 —— 调用方完全不用动。
     */
    private Map<String, ToolRisk> riskSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, ToolRisk> cached = riskCache;
        if (!cached.isEmpty() && now - riskLoadedAt < cacheTtl.toMillis()) {
            return cached;
        }
        try {
            Map<String, ToolRisk> loaded = toolMapper.selectList(null).stream()
                    .filter(e -> e.getEffectiveName() != null)
                    .collect(Collectors.toMap(
                            McpToolEntity::getEffectiveName,
                            e -> ToolRisk.parse(e.getRisk()),
                            // 同名工具理论上不可能（effective_name 是 UNIQUE），
                            // 真出现就保留先到的，不抛异常
                            (a, b) -> a));
            riskCache = loaded;
            riskLoadedAt = now;
            log.info("工具风险等级已加载: {} 条", loaded.size());
            return loaded;
        } catch (Exception e) {
            // 读库失败：返回旧缓存（哪怕过期）也好过返回空 ——
            // 返回空会让所有工具都被判成 HIGH（因为 getOrDefault 兜底），
            // 那会让所有操作都要审批，比"用稍旧的等级"糟糕得多
            log.error("读取 tools 表失败，本次沿用上一次的等级快照（{} 条）", cached.size(), e);
            return cached;
        }
    }

    /** 供第 2 步用：拿单个工具的危险等级。查不到按 HIGH */
    public ToolRisk riskOf(String clientKey, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolRisk.HIGH;
        }
        return riskSnapshot().getOrDefault(toolName, ToolRisk.HIGH);
    }
}
```

> **`Function` 那个 import 如果编译器报"未使用"，删掉即可** —— 我保留它是为了
> 你将来把 `Collectors.toMap` 展开成显式 lambda 时不用再补 import。

---

### 4.6 `ai-common/.../properties/McpProperties.java`（改造：加一个字段）

只加一个字段，其余不动：

```java
// 原有字段保持不变...
private boolean enabled = false;
private Duration toolTimeout = Duration.ofSeconds(60);
private Map<String, Server> servers = new LinkedHashMap<>();

/**
 * 工具危险等级的缓存时长。
 *
 * <p><b>它不是性能参数，是"数据新鲜度"参数。</b>
 * 值越大 → 读库越少，但管理员在网关改了 risk 之后生效越慢。
 * 默认 60 秒是个折中：改完等一分钟就能看到效果，同时把读库次数压到每分钟一次。
 *
 * <p>默认值写成字段初始化而不是靠 yml —— 少一个"服务没配就崩"的可能。
 */
private Duration riskCacheTtl = Duration.ofSeconds(60);
```

---

### 4.7 `ai-common/.../config/AiCommonAutoConfiguration.java`（改造）

**三处改动**，其余保持原样。

**改动 1**：`@MapperScan` 从单包改成双包（`McpToolMapper` 要在 `com.best.ai_common.mcp.db`）。

**改动 2**：那个嵌套配置类改名 —— 它已经不只管 token 台账了。

**改动 3**：新增 `McpToolCatalog` Bean。

```java
    // ===== 下面是改过的两个嵌套配置类，替换原来的 TokenUsageDbConfig =====

    /**
     * 所有"需要数据库支撑"的能力集中在这里。
     *
     * <p><b>为什么改名。</b> 原来叫 {@code TokenUsageDbConfig}，只扫 metrics.db 一个包；
     * 现在它还要提供 tools 表的 mapper 和工具目录。名字不改的话，
     * 下一个来找"tools 表的 mapper 在哪扫的"的人会先在这里扑空。
     *
     * <p><b>两个包的 mapper 写在同一个 {@code @MapperScan} 里</b>，
     * 而不是两个配置类各扫各的 —— 同一个 {@code @MapperScan} 分散在多处时，
     * 条件（{@code @ConditionalOnBean(DataSource.class)}）的求值时机很难推理，
     * 而 scan 漏了的症状是"启动正常、用到才报 Bean 找不到"。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({BaseMapper.class, DataSource.class})
    @ConditionalOnBean(DataSource.class)
    @MapperScan({"com.best.ai_common.metrics.db", "com.best.ai_common.mcp.db"})
    static class DbBackedConfig {

        @Bean
        @ConditionalOnMissingBean
        public TokenUsageRecorder tokenUsageRecorder(AiTokenUsageMapper mapper) {
            return new DbTokenUsageRecorder(mapper);
        }

        /**
         * 工具目录：合并 MCP 协议的工具清单与网关库的风险等级。
         *
         * <p><b>两个条件都要满足</b>：要有数据库（读 risk），也要开了 MCP（才有客户端）。
         * 只满足一个的话，这个 Bean 自身是没意义的 ——
         * 没有 MCP 客户端就没有工具清单，没有库就查不到等级。
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
        public McpToolCatalog mcpToolCatalog(McpClientRegister register,
                                            McpToolMapper toolMapper,
                                            McpProperties properties) {
            return new McpToolCatalog(register, toolMapper, properties.getRiskCacheTtl());
        }
    }
```

**import 变化**（补这些）：

```java
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.mcp.db.McpToolMapper;
// 原有的 McpProperties / McpClientRegister / AiTokenUsageMapper / DbTokenUsageRecorder
// / TokenUsageRecorder / BaseMapper / DataSource / MapperScan 都还在，不用动
```

> ⚠️ **原来的 `McpToolProvider` Bean 保留不动。** 它被 `assistant-demo/AssistantConfig`
> 用着（那边的普通助手需要"模型自动执行工具"）。它和本方案**不冲突**：
> 区别只在于"谁来执行工具"。但**ai-multiagent 绝不能注入它** ——
> 一旦注入并把工具挂上 `AiServices`，工具就会在专家节点内部被执行，第 2 步的审批就没意义了。

---

### 4.8 `ai-multiagent/.../agent/SupervisorAgent.java`（改造）

```java
package com.best.multiagent.agent;


import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    /**
     * 路由目标。
     *
     * <p><b>划分依据从"有没有副作用"改成了"属于哪个服务"。</b>
     * 改动原因：每个专家的工具由<b>网关分发的 token</b> 决定，
     * 而 token 是按<b>服务</b>授权的（`token_services` 表）。原来按"有没有副作用"分，
     * 会出现"判定为查询 → 交给 WITHOUT_SIDE_EFFECT 的专家 → 但那个专家的 token
     * 根本没有这个服务的工具"这种死局：专家手里没工具，只能编答案。
     *
     * <p><b>常量名要和 `ai.mcp.servers` 的 key 对应</b>（例如 {@code ORDER}
     * 对应 {@code ai.mcp.servers.order}）。这个约定在 {@code ExpertNode} 里被用到 ——
     * 见 {@code MultiAgentGraphConfig} 的注释。用约定而不是加一层映射表，
     * 是因为"这个专家能用的工具"和"这个 token 授权的工具"本来就应该是一回事，
     * 分开配置反而会漂移。
     *
     * <p><b>枚举值的 {@code @Description} 会原样进提示词</b>
     * （LangChain4j 的 {@code EnumOutputParser.formatInstructions()}），
     * 所以这里写的话术直接决定路由准确率 —— 写得越具体越好。
     */
    enum Routing {
        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER,

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS,

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE,

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH
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

---

### 4.9 `ai-multiagent/.../agent/ExpertAgent.java`（改造）

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
     * <p><b>为什么返回结构化的 record 而不是 String。</b>
     * 因为这里有两个职责：既要回答用户，又要<span>提议</span>一个工具调用。
     * 如果返回自由文本，调用方就得去解析文本 —— 那是最脆的一类代码
     * （模型换个措辞就解析失败）。返回 record 之后由框架负责按 JSON 解析，
     * 格式对了就是对了。
     *
     * <p><b>注意：这里刻意不挂 {@code .tools(...)}。</b>
     * 挂了之后 LangChain4j 会<span>当场执行</span>工具（在 AiServices 内部），
     * 而审批是跨 HTTP 请求的异步动作 —— 工具已经跑了才问"要不要批准"是没有意义的。
     * 所以专家只<span>提议</span>，执行交给图里的独立节点。
     * 见 {@code ToolExecuteNode}。
     */
    @SystemMessage("""
            你是{{role}}。

            ## 你可以调用的工具
            {{tools}}

            ## 已知的用户信息（可能为空）
            {{memory}}

            请针对用户的请求给出处理意见。

            关于工具：
            - 需要调用工具时，在 tool 字段填工具名、arguments 字段填参数对象
            - 不需要调用工具时，tool 填 NONE、arguments 填空对象
            - 只能从上面的工具清单里选，不要编造工具名
            - 不要假装已经执行了 —— 真正的执行由后续环节完成
            """)
    ExpertProposal propose(@V("role") String role,
                           @V("tools") String tools,
                           @V("memory") String memory,
                           @UserMessage String request);

    /**
     * 专家的产出。
     *
     * <p>{@code reply} 和 {@code tool} 放在同一个 record 里而不是分两次调用，
     * 是因为它们来自同一次模型推理 —— 分两次会拿到两个不一致的结论
     * （第二次问"你要调什么工具"时，模型的上下文已经变了）。
     *
     * <p>{@code arguments} 用 {@code Map<String, Object>} 而不是 String：
     * MCP 那边要的是 JSON 文本，但让模型把 JSON 作为<span>字符串字段值</span>输出
     * 会触发双重转义（{@code "arguments": "{\"a\":1}"}），模型经常写错。
     * 用 Map 则由 Jackson 直接序列化，嵌套结构天然正确。
     */
    record ExpertProposal(

            @Description("给用户的处理意见或结论，用中文，简洁")
            String reply,

            @Description("要调用的工具名，必须从给定的工具清单里选；不需要调用工具时填 NONE")
            String tool,

            @Description("工具调用参数，JSON 对象；不需要调用工具填空对象")
            Map<String, Object> arguments
    ) {
    }
}
```

> **顺手修掉了原来的笔误**：原 `@SystemMessage` 结尾是 `决定""")` ——
> 多了两个引号。它在 text block 里合法、能编译，但会**原样进提示词**。

---

### 4.10 `ai-multiagent/.../node/ExpertNode.java`（改造）

```java
package com.best.multiagent.node;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.mcp.McpTool;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.message.ChatMessageTexts;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.state.MultiAgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ExpertNode implements NodeAction<MultiAgentState> {

    /** 约定的"不调用工具"标记。和提示词里写的一致 */
    private static final String NO_TOOL = "NONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 提示词里显示的角色名，例如"订单助手" */
    private final String role;

    /**
     * 这个专家用哪个 MCP 客户端 —— 也就是 {@code ai.mcp.servers} 的 key。
     * <p>它同时决定了：能看到哪些工具（网关按 token 过滤）、执行时用哪个客户端。
     * <b>一个值管两件事</b>，所以不存在"看到的工具"和"能执行的工具"不一致的可能。
     */
    private final String clientKey;

    private final ExpertAgent expertAgent;

    private final McpToolCatalog toolCatalog;

    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String request = ChatMessageTexts.lastUserText(state.messages());

        // 每次请求都重新取工具清单 —— 目录内部有 TTL 缓存，这里不用自己管
        List<McpTool> tools = toolCatalog.list(clientKey);
        String toolList = renderTools(tools);

        ExpertAgent.ExpertProposal proposal;
        try (var ignored = AiUserContext.open(state.userId())) {
            proposal = expertAgent.propose(role, toolList, state.memoryContext(), request);
        } catch (Exception e) {
            // 专家失败不应该让整张图崩 —— 把失败当一次"回答"写回消息，让 supervisor 决定收尾
            log.error("专家 {} 处理失败", role, e);
            return Map.of(
                    MultiAgentState.MESSAGES, AiMessage.from("[" + role + "] 处理失败：" + e.getMessage()),
                    MultiAgentState.PROPOSED_TOOL, "");
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
     * 校验模型给的工具名。
     *
     * <p><b>为什么必须校验。</b> 模型会<span>编造</span>工具名（幻觉）。
     * 编出来的名字直接拿去执行，MCP 那边会返回"工具不存在"，
     * 而这个错误要到<span>执行节点</span>才暴露 —— 那时已经烧掉一次模型调用和一次网络往返。
     * 在这里拦掉，日志里能直接看到"模型编了个什么名字"，对调提示词也有用。
     */
    private String normalizeTool(String proposed, List<McpTool> available, String request) {
        if (proposed == null || proposed.isBlank() || NO_TOOL.equalsIgnoreCase(proposed.trim())) {
            return NO_TOOL;
        }
        String trimmed = proposed.trim();
        boolean exists = available.stream().anyMatch(t -> t.name().equals(trimmed));
        if (!exists) {
            log.warn("专家 {} 提议了一个不在清单里的工具: [{}]。可用: {}。请求: {}",
                    role, trimmed, available.stream().map(McpTool::name).toList(), request);
            return NO_TOOL;
        }
        return trimmed;
    }

    /**
     * 把工具清单渲染进提示词。
     *
     * <p>包含参数结构 —— 模型需要知道每个参数叫什么、什么类型，
     * 否则它只能瞎猜参数名。{@code JsonObjectSchema} 的 toString 就是 JSON，
     * 直接拼进去即可。
     */
    private String renderTools(List<McpTool> tools) {
        if (tools.isEmpty()) {
            return "（本次没有可用工具，请只给出文字意见）";
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
            // 序列化不了就当没有参数 —— 总比因为一个参数格式问题让整张图失败好
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

### 4.11 `ai-multiagent/.../node/ToolExecuteNode.java`（新增）★ 本单的核心

```java
package com.best.multiagent.node;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具执行节点 —— 全图唯一真正产生副作用的地方。
 *
 * <p><b>为什么工具要单独成一个节点，而不是在专家里顺手执行。</b>
 * 因为 langgraph4j 的"停下等人批准"是<b>节点级</b>的能力：
 * {@code InterruptibleAction.interrupt()} 在<b>节点执行之前</b>被调用，
 * 返回有值时<b>这个节点根本不执行</b>。把执行放进专家节点内部，
 * 就没有任何地方能插进去"先问一句" —— 工具已经跑了。
 *
 * <p><b>本单（第 1 步）它恒不中断</b>，所有工具都会被直接执行。
 * 第 2 步只需要改下面 {@code interrupt()} 的方法体，图结构一行不动。
 *
 * <p><b>为什么同时实现两个接口。</b>
 * {@code addNode} 没有收 {@code InterruptibleAction} 的重载；
 * 框架是靠 {@code AsyncNodeAction.node_async()} 内部的 {@code instanceof} 检查 +
 * 动态代理把可中断性带上的。所以类上两个接口都要写，
 * 然后照常 {@code node_async(this)} 传进 {@code addNode} 即可。
 */
@Slf4j
@RequiredArgsConstructor
public class ToolExecuteNode implements NodeAction<MultiAgentState>, InterruptibleAction<MultiAgentState> {

    private static final String NO_TOOL = "NONE";

    private final McpClientRegister register;

    /**
     * 【第 1 步】恒不中断。
     *
     * <p><b>第 2 步要改成</b>：
     * <pre>
     * if (state.approvalDecided()) return Optional.empty();          // 已经问过 → 放行
     * String tool = state.proposedTool();
     * if (tool 为空 or NONE) return Optional.empty();                 // 没有提议 → 无事可做
     * ToolRisk risk = toolCatalog.riskOf(state.proposedBy(), tool);
     * if (!risk.atLeast(threshold)) return Optional.empty();          // 未达阈值 → 放行
     * return Optional.of(InterruptionMetadata.builder(nodeId, state)
     *         .reason("工具 " + tool + " 危险等级 " + risk + "，需要人工批准")
     *         .build());
     * </pre>
     *
     * <p><b>为什么先写空实现而不是直接写完整逻辑。</b>
     * 那样第 1 步就同时引入了"工具执行"和"中断判断"两个变量，
     * 出问题时无法定位。第 1 步先确认"工具真的被调用、结果真的回来了"，
     * 第 2 步再在这个确定的基础上加中断。
     */
    @Override
    public Optional<InterruptionMetadata<MultiAgentState>> interrupt(String nodeId,
                                                                    MultiAgentState state,
                                                                    RunnableConfig config) {
        return Optional.empty();
    }

    /**
     * 执行提议里的工具。
     *
     * <p><b>为什么这里不再判断"该不该执行"。</b>
     * 到这个方法时，框架已经问过 {@code interrupt()} 并且得到"可以执行"的回答 ——
     * 再判一次就是第二份真相。判断只留在 {@code interrupt()} 里一处。
     */
    @Override
    public Map<String, Object> apply(MultiAgentState state) {
        String tool = state.proposedTool();

        // 专家没提议工具（NO_TOOL）—— 直接放行，什么都不做。
        // 这是最常见的情况：纯问答、解释类请求。
        if (tool == null || tool.isBlank() || NO_TOOL.equals(tool)) {
            return Map.of();
        }

        McpClient client = register.get(state.proposedBy());
        if (client == null) {
            log.error("找不到 MCP 客户端: clientKey={}，无法执行工具 {}",
                    state.proposedBy(), tool);
            return Map.of(
                    MultiAgentState.MESSAGES,
                    AiMessage.from("[工具 " + tool + " 未能执行] 未配置 " + state.proposedBy() + " 的 MCP 通道"),
                    MultiAgentState.PROPOSED_TOOL, ""
            );
        }

        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    // id 是 MCP JSON-RPC 的关联标识。不显式给的话框架可能用空串，
                    // 某些 MCP 服务端会因此拒绝 —— 给一个 UUID 是零成本的保险
                    .id(UUID.randomUUID().toString())
                    .name(tool)
                    .arguments(state.proposedArgs())
                    .build();

            ToolExecutionResult result = client.executeTool(request);

            // isError() 是 MCP 的"工具执行失败"信号（比如 SQL 语法错），
            // 它和"网络异常"不同：网络异常会抛异常走 catch，
            // 而 isError 是工具正常返回了但业务上失败。两种都要区分对待，
            // 否则模型会以为查询成功、拿着错误信息继续编
            boolean failed = result.isError();
            String text = result.resultText();
            log.info("工具执行{}: tool={} by={} result={}",
                    failed ? "返回错误" : "成功", tool, state.proposedBy(), abbreviate(text));

            return Map.of(
                    MultiAgentState.MESSAGES,
                    AiMessage.from("[" + (failed ? "工具 " + tool + " 返回错误" : "工具 " + tool + " 结果") + "]\n" + text),
                    // 清掉提议，避免 supervisor 下一轮又把同一个工具当成"新提议"再执行一次
                    MultiAgentState.PROPOSED_TOOL, ""
            );
        } catch (Exception e) {
            // 工具失败不能崩图 —— 把失败当作"一次工具结果"写回消息，
            // 模型下一轮能看到失败原因并改用别的方式回答
            log.error("工具执行异常: tool={} by={} args={}",
                    tool, state.proposedBy(), state.proposedArgs(), e);
            return Map.of(
                    MultiAgentState.MESSAGES,
                    AiMessage.from("[工具 " + tool + " 执行失败] " + e.getMessage()),
                    MultiAgentState.PROPOSED_TOOL, ""
            );
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...(截断)";
    }
}
```

---

### 4.12 `ai-multiagent/.../state/MultiAgentState.java`（改造）

```java
package com.best.multiagent.state;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiAgentState extends AgentState {

    public static final String MESSAGES = "messages";
    public static final String USER_ID = "userId";
    public static final String SESSION_ID = "sessionId";
    public static final String NEXT = "next";
    public static final String MEMORY_CONTEXT = "memoryContext";
    public static final String APPROVED = "approved";

    /** 专家提议要调用的工具名。{@code NONE} 或空串表示"不调用工具" */
    public static final String PROPOSED_TOOL = "proposedTool";

    /** 工具参数，<b>JSON 文本</b>（MCP 的 {@code ToolExecutionRequest.arguments} 要的就是字符串） */
    public static final String PROPOSED_ARGS = "proposedArgs";

    /**
     * 提议来自哪个专家 —— 也就是 {@code ai.mcp.servers} 的 key。
     *
     * <p><b>为什么这个字段是必须的。</b> {@code ToolExecuteNode} 需要知道
     * "用哪个 MCP 客户端去执行" —— 因为每个专家用的是不同的 token，
     * 能执行的工具范围不同。而 {@code NodeAction} 的签名里没有 {@code RunnableConfig}，
     * 节点看不到当前是从哪个节点过来的，所以这个信息只能由专家写进 state。
     */
    public static final String PROPOSED_BY = "proposedBy";

    /**
     * 这次中断是不是已经被人回答过了。
     *
     * <p><b>为什么不能直接用 {@link #APPROVED} 判断。</b>
     * {@code APPROVED} 的 channel 默认值是 {@code false}，所以它
     * <b>分不清"还没有问过人"和"人拒绝了"</b>。
     * 第 2 步的 {@code interrupt()} 里如果用 {@code approved} 做"是否已问过"的判据，
     * 那么"拒绝"之后 {@code interrupt()} 会再次返回有值 → 图会<b>重复中断、卡死</b>。
     * 所以需要一个独立的、含义明确的"已决"标记。
     *
     * <p><b>第 1 步就用不上它</b>（{@code interrupt()} 恒返回空），
     * 但<b>现在就加进 SCHEMA</b>：SCHEMA 的改动会让存量 checkpoint 失效，
     * 现在库里还没有 checkpoint，是最便宜的时点。
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
        return this.<List<ChatMessage>>value(MESSAGES).orElseGet(List::of);
    }

    public String userId()          { return this.<String>value(USER_ID).orElse(""); }
    public String sessionId()       { return this.<String>value(SESSION_ID).orElse(""); }
    public String next()            { return this.<String>value(NEXT).orElse(""); }
    public String memoryContext()   { return this.<String>value(MEMORY_CONTEXT).orElse(""); }
    public boolean approved()       { return this.<Boolean>value(APPROVED).orElse(false); }
    public String proposedTool()    { return this.<String>value(PROPOSED_TOOL).orElse(""); }
    public String proposedArgs()    { return this.<String>value(PROPOSED_ARGS).orElse("{}"); }
    public String proposedBy()      { return this.<String>value(PROPOSED_BY).orElse(""); }
    public boolean approvalDecided(){ return this.<Boolean>value(APPROVAL_DECIDED).orElse(false); }
}
```

> **两处改动要单独说明：**
>
> 1. **`SESSION_ID` 的常量值从 `"session_id"` 改成了 `"sessionId"`** —— 和 `USER_ID` 的命名风格统一。
>    这个值**是 checkpoint 里的存储 key**，一旦有存量数据就改不动了，
>    所以趁现在是零数据（服务都还没跑通）改掉，成本最低。
> 2. **删掉了 `PENDING_ACTION`** —— 它原来装的是硬编码的假动作字符串，
>    现在被 `PROPOSED_TOOL` + `PROPOSED_ARGS` 取代。

---

### 4.13 `ai-multiagent/.../config/MultiAgentGraphConfig.java`（改造）

```java
package com.best.multiagent.config;

import com.best.ai_common.mcp.McpClientRegister;
import com.best.ai_common.mcp.McpToolCatalog;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemoryRecorder;
import com.best.multiagent.agent.ExpertAgent;
import com.best.multiagent.agent.SupervisorAgent;
import com.best.multiagent.graph.NamespacedKeyNamingStrategy;
import com.best.multiagent.node.*;
import com.best.multiagent.state.MultiAgentState;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    /**
     * 三位专家。角色名进提示词，clientKey 决定"能用哪些工具 + 用哪个客户端执行"。
     *
     * <p><b>clientKey 就是 {@code ai.mcp.servers} 的 key</b>，这个约定让
     * "这个专家有什么工具"完全由网关的 token 授权决定，本项目不维护映射表。
     * 去网关后台建三个 token 分别只授权 order / analysis(outline) / knowledge 之后，
     * 把它们的值填进 yml 即可 —— <b>代码一行不用改</b>。
     */
    private static final String AGENT_ORDER = "order";
    private static final String AGENT_ANALYSIS = "analysis";
    private static final String AGENT_KNOWLEDGE = "knowledge";

    private static final String NODE_TOOL_EXECUTE = "toolExecute";

    /**
     * 专家 Agent。
     *
     * <p><b>注意：只有 {@code .chatModel(...)}，没有 {@code .tools(...)}。</b>
     * 工具由 {@code ToolExecuteNode} 执行（原因见 {@link ToolExecuteNode} 的类注释）。
     * 三位专家共用<b>同一个</b> {@code ChatModel}，但<b>各自不同的提示词参数</b>
     * （role + 工具清单由 {@code ExpertNode} 在运行时注入）——
     * 所以一个 {@code ExpertAgent} 实例就够，不需要三个。
     *
     * <p>⚠️ <b>不要注入 {@code McpToolProvider}</b>：它是 ai-common 里现成的 Bean，
     * 但 {@code assistant-demo} 在用（那边的助手需要"模型自动执行工具"）。
     * ai-multiagent 用了它，工具就会在专家节点内部被执行，审批就失去意义了。
     */
    @Bean
    public ExpertAgent expertAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(ExpertAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister) {
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            ExpertAgent expertAgent,
            MemoryRecallService recallService,
            UserMemoryRecorder memoryRecorder,
            McpToolCatalog toolCatalog,
            McpClientRegister mcpRegister,
            // 工具目录依赖 McpToolMapper，而它是"有 DataSource 才存在"的 Bean ——
            // 没配数据库的服务也能启动，只是工具能力为空。
            // 用 ObjectProvider 而不是直接注入：直接注入会让"关掉数据库"变成启动失败
            ObjectProvider<McpToolCatalog> toolCatalogProvider,
            @Value("${ai.multiagent.assistant-id:${spring.application.name:multiagent}}")
            String assistantId,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${ai.multiagent.checkpoint-ttl-hours:24}") long ttlHours
    ) throws GraphStateException {

        var saverBuilder = RedisSaver.builder()
                .host(redisHost)
                .port(redisPort)
                .stateSerializer(new LC4jStateSerializer<AgentState>(MultiAgentState::new))
                .keyNamingStrategy(new NamespacedKeyNamingStrategy(assistantId))
                .ttl(ttlHours, TimeUnit.HOURS);
        if (redisPassword != null && !redisPassword.isBlank()) {
            saverBuilder.password(redisPassword);
        }
        var saver = saverBuilder.build();
        log.info("Checkpoint 已启用: RedisSaver {}:{} ttl={}h namespace={}",
                redisHost, redisPort, ttlHours, assistantId);

        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                .addNode("recallMemory", node(new RecallMemoryNode(recallService)))
                .addNode("supervisor", node(new SupervisorNode(supervisorAgent)))
                .addNode(AGENT_ORDER, node(new ExpertNode("订单助手", AGENT_ORDER, expertAgent, toolCatalog)))
                .addNode(AGENT_ANALYSIS, node(new ExpertNode("数据分析助手", AGENT_ANALYSIS, expertAgent, toolCatalog)))
                .addNode(AGENT_KNOWLEDGE, node(new ExpertNode("知识库助手", AGENT_KNOWLEDGE, expertAgent, toolCatalog)))
                // 全图唯一产生副作用的节点。第 1 步它恒不中断；第 2 步插审批就改它
                .addNode(NODE_TOOL_EXECUTE, node(new ToolExecuteNode(mcpRegister)))
                .addNode("writeMemory", node(new WriteMemoryNode(assistantId, memoryRecorder)))
                .addEdge(GraphDefinition.START, "recallMemory")
                .addEdge("recallMemory", "supervisor")
                .addConditionalEdges("supervisor",
                        AsyncEdgeAction.edge_async(MultiAgentState::next),
                        Map.of(AGENT_ORDER, AGENT_ORDER,
                                AGENT_ANALYSIS, AGENT_ANALYSIS,
                                AGENT_KNOWLEDGE, AGENT_KNOWLEDGE,
                                "FINISH", "writeMemory"))
                // 三位专家都汇到同一个执行节点 —— 因为"执行"这件事只需要一份实现，
                // 差异（用哪个客户端）由 state.proposedBy 决定
                .addEdge(AGENT_ORDER, NODE_TOOL_EXECUTE)
                .addEdge(AGENT_ANALYSIS, NODE_TOOL_EXECUTE)
                .addEdge(AGENT_KNOWLEDGE, NODE_TOOL_EXECUTE)
                // 执行完回到调度器：由它判断"任务完成了吗"，完成就 FINISH → writeMemory
                .addEdge(NODE_TOOL_EXECUTE, "supervisor")
                .addEdge("writeMemory", GraphDefinition.END);

        // ⚠️ 第 1 步刻意【不配】interruptBefore。
        // 它是编译期的无条件中断，表达不了"按危险等级决定"。
        // 第 2 步会靠 ToolExecuteNode 的 InterruptibleAction 来做条件中断。
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(25)
                .build());
    }

    private static AsyncNodeAction<MultiAgentState> node(NodeAction<MultiAgentState> action) {
        // 这一行是"可中断性"能生效的关键：
        // node_async 内部会 instanceof 检查 InterruptibleAction，
        // 是的话用动态代理再造一个同时实现两个接口的对象。
        // ToolExecuteNode 同时实现了这两个接口 → 走的就是这条路径。
        return AsyncNodeAction.node_async(action);
    }
}
```

> **三点说明：**
>
> 1. **`toolCatalog` 就直接注入，不要用 `ObjectProvider`。**
>    （本稿初版这里写的是"用 `ObjectProvider` 容错、请删掉直接注入的那个参数" ——
>    **那是错的**。原因见下面「修正」。参数列表里两个都留着是为了让你看到判断过程，
>    **落地时删掉 `ObjectProvider<McpToolCatalog>` 那个参数**。）
>
> **修正：为什么直接注入是对的**
>
> 初版的推理是"没配数据库的服务也要能启动，所以用 `ObjectProvider` 优雅降级"。
> 它漏了一个事实：**`ai-multiagent` 本来就离不开数据库** ——
> 长期记忆写 `ai_user_memory`、token 台账写 `ai_token_usage`，
> 而 `spring.datasource.url` 已经指向 `mcp_gateway` 库。
> **"没有数据库的 ai-multiagent"这个场景根本不存在。**
>
> 为一个不存在的场景加一层 `ObjectProvider`，代价是 `ExpertNode` / `ToolExecuteNode` 里
> 到处 `getIfAvailable()` + null 判断 —— 复杂度换来了一个永远不会走的降级分支。
> **直接注入更简单，也更诚实。**
>
> （真正需要容错的是 `ai-common` 这个通用底座 —— 它可能被不需要数据库的服务引用。
>  但那一层已经在 `@ConditionalOnBean(DataSource.class)` 上处理完了。）
>
> 所以 `MultiAgentGraphConfig` 的参数列表里**保留 `McpToolCatalog toolCatalog`**，
> 删掉 `ObjectProvider<McpToolCatalog> toolCatalogProvider`。
> 2. **`/approve` 端点暂时用不上了** —— 第 1 步没有任何中断点，
>    `resume` 一个不存在的中断点会报错。Controller 保留端点但不要调它，
>    第 2 步接上中断后它才会重新有意义。
> 3. **`recursionLimit(25)`** 保持不动。现在每次"专家 → 执行 → 调度"算一轮，
>    比原来多一个节点，25 仍然够用；真撞上限了再调。

---

### 4.14 `ai-multiagent/.../controller/AgentController.java`（改造）

只改 `waiting()` 里读的那个字段（`pendingAction` 已经不存在了）：

```java
    private Map<String, Object> waiting(String threadId, RunnableConfig config) {
        StateSnapshot<MultiAgentState> snapshot = graph.getState(config);
        String pending = snapshot == null ? "" : snapshot.state().proposedTool();
        return Map.of(
                "status", "waiting_approval",
                "threadId", threadId,
                "pendingAction", pending
        );
    }
```

> **第 1 步这个方法是死代码**（没有任何路径会产生中断）。留着是为了第 2 步不用重写。
> 如果你觉得死代码碍眼，可以先注释掉 —— 但**别删**，第 2 步会原样用到。

---

### 4.15 `ai-multiagent/src/main/resources/application.yml`（改造）

```yaml
ai:
  mcp:
    enabled: true
    tool-timeout: 60s
    # 工具危险等级的缓存时长（s/m/h 都行）。改了网关的 risk 之后，最多这么久生效
    risk-cache-ttl: 60s
    servers:
      # ⚠️ token 你自己去网关后台建，名字和权限我在这里留位置：
      #
      #   excel/order      → 只勾选 token_services 里的「订单mcp」
      #   analysis         → 只勾选「数据分析mcp」
      #   knowledge        → 只勾选「知识库mcp」
      #
      # 三个 token 的 allow_high_risk 建议都【打开】——
      # 网关那层只管"能不能调"，"该不该问人"是下游的事（网关源码注释原话）。
      # 关掉的话高危工具直接在网关侧 403，你就看不到"停下来等审批"的流程了。
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
```

> ⚠️ **明文凭据绝不写进 yml。** 用 `${...}` 从环境变量读 ——
> 这是项目已有纪律（22 号配置分层方案里定的：共享配置会被分发到每个服务，
> 明文 token 绝不能进去）。
>
> **在三个 token 建好之前**，你可以先用现在这个全权 token（`gw_KFSjH...`）
> 把三个 key 都指向它 —— 三个专家都会看到全部 4 个工具，
> 足以验证"工具链路是通的"。等 token 建好再逐个换掉，**代码不用动**。

---

## 5. 验收

```bash
# ① 编译（这一步能抓出所有 API 用错）
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile

# ② 确认 MCP 客户端按 key 建起来了（启动日志）
#    期待看到 3 行 "MCP 服务端已连接: name=order/analysis/knowledge"
#    和 1 行 "工具风险等级已加载: 4 条"

# ③ 起服务后，走一次"需要调工具"的请求
curl -s -X POST http://localhost:8081/agent/chat \
  -H 'Content-Type: application/json' -H 'X-User-Id: u1' \
  -d '{"message":"帮我查一下订单号 12345 的状态","sessionId":"s1"}'
```

**看日志里有没有这几行**（这是本单的验收标准）：

```
专家 订单助手 提议工具: order__searchMultipartBizOrder 参数: {"...": "..."}
工具执行成功: tool=order__searchMultipartBizOrder by=order result=...（截断）
```

**再看数据库，确认 checkpoint 里存下了新字段**：

```bash
# 用你惯用的方式连 Redis
redis-cli --scan --pattern 'langgraph4j:ai-multiagent:*'
# 取一个 checkpoint 看内容，里面应该能看到 proposedTool / proposedBy 字段
```

**如果模型没提议工具**（打了 `提议工具: NONE`），先看这两件事：
1. 提示词里工具清单渲染出来了没有（`renderTools` 的输出）
2. 工具名有没有在 `ai.mcp.servers` 对应的 token 授权范围内

---

## 6. 自查清单

**编译期**
- [ ] `mvn -o clean compile` 零错误（**特别是 `InterruptibleAction` 的拼写 —— 带 i**）
- [ ] `MultiAgentGraphConfig` 里没有残留的 `McpToolCatalog toolCatalog` 直接注入参数
- [ ] `ApprovalGateNode` 的 import 已从 `MultiAgentGraphConfig` 移除（文件本身保留）

**启动期**
- [ ] 启动日志有 3 条 MCP 连接成功 + 1 条工具等级加载
- [ ] 没有出现 `interruptionNodeNotExist`（说明 `interruptBefore` 已删干净）

**运行期**
- [ ] 日志出现 `专家 X 提议工具: <真实工具名>`
- [ ] 日志出现 `工具执行成功` 且 result 里有真实数据
- [ ] 工具执行后 supervisor 能拿到结果并收尾（不是死循环）
- [ ] **纯问答请求**（比如"你好"）不会触发工具执行（应该打 `提议工具: NONE`）

**边界**
- [ ] 把 `ai.mcp.servers.order` 改成一个错的 token → 应该看到 warn 日志 + 该专家无工具，
      而**不是整个请求失败**
- [ ] 手动把库里某个工具的 risk 改成 `critical` → 应该出现一次 `未知 risk 值: [critical]`
      的 warn，且**只出现一次**（不刷日志）

---

## 7. 下一步（第 2 步预告）

第 2 步只改三个地方：

| # | 改动 | 位置 |
|---|---|---|
| 1 | `interrupt()` 方法体（查 risk + 比阈值） | `ToolExecuteNode` |
| 2 | 加 `approval-min-risk` 配置项 | `application.yml` + 读取 |
| 3 | 恢复 `/approve` 端点的用途；`saveApproval` 时写 `approvalDecided=true` | `AgentController` |

**图结构、state SCHEMA、所有新增类都不动。** 这就是第 1 步先把拓扑定稿的价值。

第 2 步的两个关键点先记在这里，到时候不用重新推导：

1. **`interrupt()` 必须判 `approvalDecided`**（不是判 `approved`）——
   因为 `approved` 默认 false，分不清"没问过"和"拒绝了"，判错会导致重复中断、图卡死。
2. **批准后 resume，图会重新经过 `toolExecute` 节点**，此时 `interrupt()` 返回空、
   `apply()` 执行工具 —— 所以 `apply()` 里**不要**再写"如果没批准就跳过"的判断，
   那是第二份真相。
