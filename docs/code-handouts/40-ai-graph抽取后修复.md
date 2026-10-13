# 40 · 抽 `ai-graph` 之后的编译修复

> **前置**：39 号已落地（`ai-graph` 建好，`ai-multiagent` 瘦身完成）。
>
> **本文档回答**：`AiGraphAutoConfiguration` 里 `redisProperties` 为什么标红 + 一次完整审查的结果。

---

## 0. 先回答 `redisProperties` 标红 —— 它不是代码问题

**证据（两条，都是实测）：**

```
# ① RedisProperties 确实存在
spring-boot-autoconfigure-3.5.16.jar
  └── org/springframework/boot/autoconfigure/data/redis/RedisProperties.class   ✓

# ② ai-graph 单独编译，BUILD SUCCESS
mvn -o -DskipTests -pl ai-graph -am clean compile
  → ai-graph ........................................... SUCCESS
```

**所以是 IDEA 没把 `spring-boot-autoconfigure` 加进 `ai-graph` 的 classpath。**

**为什么会这样 —— 而且是同一个原因让下面三个 P0 一起出现的：**

> **整个 reactor 编译是失败的**（`ai-multiagent` 报了几十条错）。
> IDEA 的 Maven 导入遇到 reactor 失败时，会**拒绝/延迟刷新模块依赖** ——
> 于是新建的 `ai-graph` 拿不到它的传递依赖，`RedisProperties` 就红了。
>
> **修好下面那三个 P0 → 刷新 Maven → 标红大概率自己消失。**
> 如果还红，再做一次 `Maven 面板 → Reload All Maven Projects`。

**注意不要做的事**：不要为了消红去改 import、也不要给 `ai-graph` 单独加 `spring-data-redis` 依赖。
**红的是 IDE 的索引，不是代码。**

---

## 1. 真正的问题：5 处

| # | 级别 | 位置 | 症状 |
|---|---|---|---|
| **1** | **P0** | `ai-multiagent/pom.xml` | **只剩 1 个依赖** → 编译失败（缺 4 个） |
| **2** | **P0** | `AgentController` 的 3 行 import | 编译失败（指向已搬走的包） |
| **3** | **P0（静默）** | `application.yml` 的 `ai.multiagent:` | ★ **配置全部不生效，但三处碰巧都等于默认值 → 完全看不出** |
| **4** | P1 | `ai-multiagent/agent/ExpertAgent.java` | 死代码，且和 `ai-graph` 的那份**同名两份** |
| **5** | P2 | `MultiAgentGraphConfig` 的 3 个 import | 未使用（警告级） |

---

### 问题 1：`ai-multiagent/pom.xml` 只剩一个依赖

**现在是这样：**

```xml
    <dependencies>
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-graph</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
```

**39 号里我写的是"把 `ai-memory` 换成 `ai-graph`，其余不动"** —— 但**没有列出"其余"是哪几个**。
于是"换掉一个"被执行成了"只留一个"。**这是我的文档缺陷，第 5 节复盘。**

**缺的四个，各自的报错/后果：**

| 缺什么 | 症状 |
|---|---|
| `spring-boot-starter-web` | `程序包org.springframework.web.bind.annotation不存在` |
| `lombok` | `程序包lombok不存在` —— `@Slf4j` / `@RequiredArgsConstructor` 全废 |
| `mysql-connector-j` | **编译能过**，跑到 `datasource` 初始化才报 `Failed to load driver class com.mysql.cj.jdbc.Driver` |
| `langgraph4j-core` + BOM | Controller 直接 import `org.bsc.langgraph4j.*`。**现在靠 `ai-graph` 传递依赖"碰巧"能编过** —— 但那是隐式的 |

> **为什么 `langgraph4j-core` 要单独说。** 它现在通过 `ai-graph` 传递可用（compile scope 会传递），
> **编译能过**。但"我直接 import 的库"就该"我直接声明" ——
> 哪天 `ai-graph` 把 langgraph4j 换成别的实现、或者改成 `optional`，
> 这里会突然编译失败，而**原因看起来和这个文件毫无关系**。
>
> ⚠️ **并且：`ai-multiagent` 原来的 `<dependencyManagement>`（langgraph4j-bom）也被删了** ——
> 没有它，下面声明 `langgraph4j-core` 会因为**没有版本号**而报
> `'dependencies.dependency.version' is missing`。所以两处要一起加回来。

**完整的 `ai-multiagent/pom.xml`（直接整个替换）：**

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

    <artifactId>ai-multiagent</artifactId>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <!-- ★ 必须留着：下面声明 langgraph4j-core 时不写版本号，版本从这里来 -->
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
        <!-- 框架。传递带来 ai-memory → ai-common -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>ai-graph</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>

        <!-- Controller 直接 import org.bsc.langgraph4j.* → 直接声明，不赖传递 -->
        <dependency>
            <groupId>org.bsc.langgraph4j</groupId>
            <artifactId>langgraph4j-core</artifactId>
        </dependency>

        <!-- 本模块是 web 服务（AgentController） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- 运行期数据库驱动。scope=runtime：编译期不需要它 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

> **相对现在的版本，"新增"的是这 5 段**：
> ① `<dependencyManagement>` 整段（langgraph4j-bom）
> ② `langgraph4j-core` 依赖
> ③ `spring-boot-starter-web` 依赖
> ④ `mysql-connector-j` 依赖
> ⑤ `lombok` 依赖
>
> 其余（`parent` / `artifactId` / `properties` / `<build>`）**保持原样不动**。

**没有加回的（故意）：**
- ~~`langgraph4j-redis-saver`~~ —— `RedisSaver` 只在工厂里用，已经搬进 `ai-graph`
- ~~`langgraph4j-langchain4j`~~ —— 本模块不直接用它，`LC4jStateSerializer` 在 `ai-graph` 里

---

### 问题 2：`AgentController` 的 3 行 import 指向已搬走的包

**改这三行**（文件第 3~5 行）：

```java
- import com.best.multiagent.graph.GraphNodes;
- import com.best.multiagent.graph.GraphThreadIds;
- import com.best.multiagent.state.MultiAgentState;
+ import com.best.ai_graph.graph.GraphNodes;
+ import com.best.ai_graph.graph.GraphThreadIds;
+ import com.best.ai_graph.state.MultiAgentState;
```

**文件其余部分一行都不用动**（39 号 4.7 节给的就是"只改 import"）。

---

### 问题 3：`application.yml` 的前缀没改 —— ★ **完全静默，最危险的一处**

**现状（第 74 行）：**

```yaml
  multiagent:
    # 长期记忆的归属标识，和 assistant-demo 的不同
    assistant-id: ai-multiagent
    # checkpoint 的 TTL（小时）
    checkpoint-ttl-hours: 24
```

**改成：**

```yaml
  graph:                     # ← 只改这一个词
    # 长期记忆的归属标识，和 assistant-demo 的不同
    assistant-id: ai-multiagent
    # checkpoint 的 TTL（小时）
    checkpoint-ttl-hours: 24
```

**为什么这处"看起来只是改个名字"，实际是 P0：**

`GraphProperties` 现在读的是 `ai.graph.*`。yml 里写 `ai.multiagent.*` → **这三项全部回落到默认值**。而默认值恰好是：

| 配置项 | yml 里写的 | 默认值 | 结果 |
|---|---|---|---|
| `assistant-id` | `ai-multiagent` | `spring.application.name` = **`ai-multiagent`** | **一字不差** |
| `checkpoint-ttl-hours` | `24` | `24` | **一样** |
| `approval-min-risk` | （没配） | `HIGH` | **一样** |

**三处全部碰巧相等 → 服务跑起来一切正常，日志、接口、行为都看不出任何异常。**

**而它的真面目是**：你以为配置生效了，其实一行都没读到。哪天有人把 `checkpoint-ttl-hours` 改成 `48`、或者把 `approval-min-risk` 改成 `medium` —— **改了不生效，而且不报错**。

> **这正是这个项目反复踩的那类坑的第 N 次**：
> **一个"绑定了但绑错了"的配置，比"完全没绑定"更难发现** ——
> 因为"完全没绑定"至少会在启动时报 `not bound`，而"绑到别的 key 上"是静默的。
>
> **顺手可以加一道防线**：`GraphProperties` 上加 `@Validated`，或者在自动装配里
> 打一行日志把**生效值**打出来（39 号工厂里已经有 `建图完成: ... 审批阈值=HIGH` 那行 ✓
> —— **这就是它存在的意义，别删**）。

---

### 问题 4：`ai-multiagent/agent/ExpertAgent.java` 是死代码

**删掉它**：`ai-multiagent/src/main/java/com/best/multiagent/agent/ExpertAgent.java`

它已经搬到 `com.best.ai_graph.agent.ExpertAgent`。留着是**两份同名不同包的接口** ——
将来改提示词骨架时改了 A 发现不生效（因为跑的是 B），这是最难查的一类问题。

删掉之后 `ai-multiagent/agent/` 下只剩 `SupervisorAgent.java` ✓

---

### 问题 5：`MultiAgentGraphConfig` 的 3 个未使用 import（警告级）

现在的 import 里有 3 行已经用不到了（它们随建图逻辑一起搬走了）：

```java
- import com.best.ai_common.mcp.McpClientRegister;
- import com.best.ai_common.mcp.McpToolCatalog;
- import com.best.ai_common.mcp.ToolRisk;
```

**只产生警告，不影响编译**。但建议删 —— 它们会让人以为"这个类还管 MCP"。

**修完之后 `MultiAgentGraphConfig` 的 import 应该是 9 行：**

```java
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
```

> 注意 `org.bsc.langgraph4j.*` 也顺手展开成了两个具体类 —— 通配 import 在只有两个类的场景下没有价值。

---

## 2. 修完之后：`find` 应该只剩这些

```
ai-multiagent/src/main/java/com/best/multiagent/
├── MultiAgentApplication.java
├── agent/SupervisorAgent.java
├── config/MultiAgentGraphConfig.java
├── controller/AgentController.java
└── expert/{Order,Analysis,Knowledge}Expert.java
```

**5 个目录，8 个文件**（`node/` `state/` `graph/` 三个包应该已经不存在了 —— 实测**确实已删干净** ✓）

---

## 3. 验收

### 3.1 编译

```bash
cd /Users/best/project/ai_platform && \
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home && \
/opt/homebrew/bin/mvn -o -DskipTests clean compile
```

**期望**：5 个模块全 `SUCCESS`，**0 个 ERROR**。

应该还有 5 条这个 WARNING（**不是问题，不用管**）：

```
[WARNING] 未知的枚举常量 com.baomidou.mybatisplus.annotation.IdType.AUTO
  原因: 找不到com.baomidou.mybatisplus.annotation.IdType的类文件
```

> 这是 `ai-common` 里 MyBatis-Plus 实体在 `ai-graph` 的 classpath 上**只有编译期可见的一部分**
> （`mybatis-plus-spring-boot3-starter` 在 `ai-memory` 里是 `optional`，不传递）。
> **它出现在 `ai-graph` 编译时、但不影响产物** —— 因为那些实体类不在 `ai-graph` 里。
> 要是想彻底消掉，给 `ai-graph` 也加 `mybatis-plus` 的 optional 依赖即可，**但没必要**。

### 3.2 启动

```bash
/opt/homebrew/bin/mvn -o -pl ai-multiagent spring-boot:run
```

**日志里必须同时有这两行** —— 第二行就是问题 3 的验收：

```
建图完成: assistantId=ai-multiagent 专家数=3 审批阈值=HIGH
```

以及 Spring Boot 的配置绑定日志里应该有 `GraphProperties` 被绑定（不报 `not bound`）。

**★ 验证问题 3 真的修好了 —— 改一个值试试：**

```yaml
  graph:
    checkpoint-ttl-hours: 48        # 临时改成 48
```

重启，日志应该从 `ttl=24h` 变成 `ttl=48h`（在 `Checkpoint 已启用: RedisSaver ... ttl=...h` 那行附近）。

> **如果改了没变** —— 说明前缀还是错的。**这一步是唯一能证明配置真的被读到的检查，别省。**
> （因为默认值恰好等于你原来配的值，肉眼对比 yml 是发现不了的。）

### 3.3 四条用例 + 硬指标（和 39 号 6.2 节一致）

```bash
curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"查一下订单 12345 的状态","sessionId":"r1"}'   # → done

curl -s -X POST http://localhost:8081/agent/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: u1' -d '{"message":"统计库里订单记录总数","sessionId":"r2"}'      # → waiting_approval

curl -s -X POST http://localhost:8081/agent/approve -H 'Content-Type: application/json' \
  -d '{"sessionId":"r2","approved":true}'
grep -c "工具执行成功" <日志>        # 恰好 1

# 再发一次高危 → 必须仍 waiting_approval（验 approvalDecided 重置）
grep -c "调度决定" <日志>            # ≤ 2
```

### 3.4 模块边界（39 号 6.3 节，重跑一次）

```bash
grep -rn "com\.best\.multiagent" ai-graph/src/ | wc -l     # 必须 0
```

---

## 4. 自查清单

**pom**
- [ ] `ai-multiagent/pom.xml` 有 `<dependencyManagement>`（langgraph4j-bom）
- [ ] `ai-multiagent/pom.xml` 有 5 个依赖：`ai-graph` / `langgraph4j-core` / `spring-boot-starter-web` / `mysql-connector-j`(runtime) / `lombok`(optional)
- [ ] `langgraph4j-core` **没有**写 `<version>`（版本来自 BOM）

**代码**
- [ ] `AgentController` 的 3 行 import 指向 `com.best.ai_graph.*`
- [ ] `ai-multiagent/agent/ExpertAgent.java` **已删除**
- [ ] `MultiAgentGraphConfig` 里没有 `McpClientRegister` / `McpToolCatalog` / `ToolRisk` 的 import

**配置**
- [ ] `application.yml` 里是 `ai.graph:`（不是 `ai.multiagent:`）
- [ ] **3.2 节那个"改 48 看日志"的验证真的做了一次**

**IDE**
- [ ] 编译全绿之后，`Maven 面板 → Reload All Maven Projects`
- [ ] `AiGraphAutoConfiguration` 里的 `redisProperties` 不再标红

---

## 5. 复盘：为什么会出现"只留一个依赖"

**39 号里我写的是：**

> ### 2.3 `ai-multiagent/pom.xml` —— 换一个依赖
> 把 `ai-memory` 换成 `ai-graph`（后者传递带来前者）。
> 其余不动（`langgraph4j-core` / `langchain4j-langchain4j` 保留 —— Controller 直接 import 了 `org.bsc.langgraph4j.*`；`langgraph4j-redis-saver` **可以删**）。

**问题在于我给的是"相对现在的差异"（换哪个、删哪个），而没有给"改完之后这个文件长什么样"。**
"其余不动"这四个字，在**只有一件事在改**的时候够用；但这个文件同时要动 **1 换 + 1 删 + 5 留** ——
"留什么"没有被写成清单，于是执行时只剩下了被明确点到名的那一个。

> **这是我第三次犯同一类错了：**
>
> | 次数 | 形态 |
> |---|---|
> | 30 号 | 让"完整替换"一个文件，但**没列"哪几行是新增的"** → 关键两段漏敲 |
> | 31 号 | 让"把多处按同样方式改掉"，但**没逐处列出** → 只改了最显眼的一处 |
> | **40 号** | 说"换一个依赖，其余不动"，但**没给"其余"的清单** → 只留了被点名的那一个 |
>
> **三次的共同点**：我都写清了"**要改什么**"，都没写清"**改完之后应该是什么样**"。
>
> **规则收紧成一句话**：
>
> > **给"替换 / 删除 / 移动"类指令时，必须同时给出"操作完成后的完整状态"。**
> >
> > 因为"要改什么"是**动作**，"改完什么样"是**目标** —— 只有动作没有目标，
> > 执行者就只能按字面理解，而"其余不动"这四个字，越到后面越不可靠。
>
> 所以本单的第 1 节直接给**完整的 pom.xml**，而不是"加这几段"。

---

## 6. 顺带一个发现（不在本单范围，但值得记）

`application.yml` 里 `ai.mcp.servers.*.headers.Authorization` 的 token 是**明文**写在文件里的
（39 号给的是 `Bearer ${MCP_TOKEN_ORDER}` 环境变量形式）。

**这件事本单不动** —— 因为你正在调链路，改成环境变量会让每次调试都要先 export。
但留个触发点：

> **什么时候该改成环境变量：当这个仓库要被第二个人 clone、或者要进 CI 的时候。**
>
> 在那之前它只是一个"尚未兑现的待办"；在那之后它就是一次**凭证泄露**。
> 而且它进过 git 历史 —— 那时光改文件不够，**token 得在网关后台重新签发**。

---

## 7. 最后一件事

修完之后，值得回去看一眼 39 号第 9 节那张表里的最后一段：

> **抽模块解决的是"同一个坑不要在第二个应用里再踩"，不是"第一个应用不会踩"。**

**这一单就是那句话的实例。**

`ai-graph` 建得很干净 —— 编译通过、业务零残留、`grep` 干净。
**但 `ai-multiagent` 还是坏了三个地方**，而且其中**最危险的那个（yml 前缀）和 `ai-graph` 一点关系都没有**。

**框架的质量和"用框架的那个应用"的质量，是两件事。**
这也是为什么 39 号里我把 `grep com.best.multiagent ai-graph/src/` 列为"模型边界"的判据 ——
它证明的是**框架侧**干净，**证明不了使用者侧正确**。
