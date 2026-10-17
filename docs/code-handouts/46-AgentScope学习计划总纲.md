# 46 · AgentScope Java 学习计划总纲（Spring Boot + 模型注册表版）

> 这是 AgentScope Java 学习系列的第一篇。系列共 17 篇（46–62）：1 篇总纲 + 15 章功能课 + 1 个毕业项目。
> **每章吃透一个功能**，每篇都是完整代码，你自己敲进项目。
>
> ⚠️ **2026-10-16 第二次修订**：模型层从「starter + properties」改为 **自建注册表**（对齐 ai-common 的
> `ModelRegister` 形态）。原因与实测见 §5.1。**不再依赖 `agentscope-openai-spring-boot-starter`。**
> 本篇与 47/48 已按新形态重写；49 不受影响。

---

## 0. 这个系列怎么用

1. **先读本篇**，把应用骨架敲出来（两个 pom + yml + 四个骨架类），跑通 §10 的准备
2. 然后按章敲：47 → 48 → …，每章结尾有检查点（**curl + 预期响应**），过了再下一篇
3. 卡住时贴三样：报错全文（含 `Caused by:`）、你敲的那一节、curl 命令及输出

每章固定结构：本章吃透什么 → 文件清单 → 完整代码（逐行讲为什么）→ 运行 → 检查点 → 自查清单 → 下一篇预告。

---

## 1. 学习模块的形态：Spring Boot 应用 + 自建模型注册表

| 项 | 值 |
|---|---|
| 模块名 / 包名 | `ai-agentscope` / `com.best.ai_agentscope` |
| 应用类型 | **Spring Boot Web（MVC）**，`mvn spring-boot:run` + curl 验收 |
| 每章一个包 | `ch01_lifecycle` … `ch15_channel`；毕业项目 `capstone` |

**分层**（15 章都长在这上面）：

```
┌─ Controller（每章业务入口）────────────────────┐
│  注入 HarnessAgent.Builder / ModelRegistrar     │
├─ 我们的 @Configuration ────────────────────────┤
│  HarnessAgent.Builder：.model("fast") 按名引用  │
├─ ★ 模型注册表（本次新增，取代 starter）─────────┤
│  ModelProperties(yml) → ModelRegistrar          │
│  → OpenAIChatModel 实例 → ModelRegistry.register│
├─ agentscope-harness（SDK，零 Spring 依赖）──────┤
└────────────────────────────────────────────────┘
```

**为什么是 MVC 不是 WebFlux**：和项目现状一致（现有模块全是 MVC + SseEmitter）。AgentScope 底层是 Reactor，MVC 控制器里 `.block()` 正当（一请求一线程）；第 3 章的 SSE 端点展示不阻塞的流式形态。

**边界声明**：这是**学习舱**，不 import `ai-common` / `ai-memory`。但注册表这个形态是**刻意对齐 ai-common 的 `ModelRegister`** 的——同一套心智，将来真接回项目时不陌生。模型端点可以指向公司的 ai-gateway（OpenAI 兼容），那是 yml 一行配置，不产生 Java 依赖。

---

## 2. 版本与坐标

| 坐标 | 说明 |
|---|---|
| `io.agentscope:agentscope-harness` | 入口（自带 agentscope-core） |
| `io.agentscope:agentscope-extensions-model-openai` | OpenAI 兼容协议模型实现 |
| `io.agentscope:agentscope-bom` | 统一管版本，**2.0.4** |
| ~~`agentscope-openai-spring-boot-starter`~~ | **本次修订已移除**（见 §5.1） |

- 版本锁定 **2.0.4**（不用 `2.1.0-BETA1`：学习要稳定 API）
- **已实测**（2026-10-16）：上述坐标可从 Central 拉取；按本篇形态搭建的 Spring Boot 应用**真实启动成功**，日志首行打印 `[model-registry] 已注册模型：[strong, fast]`
- 升级触发条件：2.1.x 正式版 + change-log 无破坏性变更；届时全系列复核，**不要单独升**

---

## 3. 根 pom 是「替换」：相对上一版的变化

根 `pom.xml` 已存在，本篇给**改后的完整文件**。相对上一版只有 **2 处新增**：

| # | 位置 | 新增 |
|---|---|---|
| 1 | `<modules>` | `<module>ai-agentscope</module>` |
| 2 | `<properties>` | `<agentscope.version>2.0.4</agentscope.version>` |

---

## 4. 根 pom 完整内容（改后）

**文件路径：`/Users/best/project/ai_platform/pom.xml`**（完整替换）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.best</groupId>
    <artifactId>ai_platform</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <modules>
        <module>ai-common</module>
        <module>ai-memory</module>
        <module>assistant-demo</module>
        <module>ai-orchestrator</module>
        <module>ai-orchestrator-demo</module>
        <module>ai-agentscope</module>
    </modules>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <langchain4j.version>1.20.0</langchain4j.version>
        <langgraph4j.version>1.9.2</langgraph4j.version>
        <agentscope.version>2.0.4</agentscope.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

</project>
```

---

## 5. 模块 pom 完整内容（替换）—— 去 starter

**文件路径：`/Users/best/project/ai_platform/ai-agentscope/pom.xml`**

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
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>ai-agentscope</artifactId>

    <!-- AgentScope BOM：版本统一管（harness / extensions / starter 全家桶） -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.agentscope</groupId>
                <artifactId>agentscope-bom</artifactId>
                <version>${agentscope.version}</version>
                <scope>import</scope>
                <type>pom</type>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Web（MVC）。版本由 Boot parent 管 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- AgentScope SDK：HarnessAgent 入口。零 Spring 依赖 -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-harness</artifactId>
        </dependency>

        <!-- ★ 直接依赖模型实现（不再经 starter 传递）。
             OpenAIChatModel 由我们自己在 ModelRegistrar 里显式构造 -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-model-openai</artifactId>
        </dependency>

        <!-- 让 IDE 认识我们的 ai.* 配置（可选，但强烈建议） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Lombok：全程用 @Slf4j 打日志（不用 System.out）。版本由 Boot parent 管 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

</project>
```

### 5.1 为什么去掉 starter（本次修订的核心决定）

**动因**：starter 只自动装配**一个** `OpenAIChatModel`，而我们要的是「多模型注册表，按名取用」——和 ai-common 的 `ModelRegister` 同构。硬用 starter 的话，多模型要靠一堆 `@Bean` + `@Qualifier`，注入点全得改，还会撞 `@ConditionalOnMissingBean(Model)` 的覆盖坑。

**去掉后拿到的**（都有实测支撑）：

| 项 | 说明 |
|---|---|
| 多模型天然支持 | yml 里配 `fast` / `strong` / …，注册表按名取；agent 用 `.model("fast")` 声明式引用 |
| 无隐藏条件 | 原 starter 的「三把钥匙」（`agentscope.model.provider` 等）全部不再需要——那是自动装配的代价，现在没有自动装配了 |
| 错误更早更清楚 | 规格缺失在**启动期**报（`ModelSpec` 校验），而不是第一次调用吃 401 |
| 与项目同构 | 注册表形态 = ai-common 的 `ModelRegister`，团队心智连续 |

**代价**（诚实列出）：模型不再由 Spring 管生命周期，要自己写注册器；properties 绑定要自己声明一个类。两点加起来约 60 行，一次写完 15 章复用。

**保留的证据**：去掉 starter 后，`.model("fast")` 走的是 AgentScope 原生 `ModelRegistry`——静态注册表，解析顺序「命名实例 → 正则工厂 → SPI」。实测：注册后 `resolve("fast")` 返回同一实例；未注册时 `.model("fast")` **当场抛** `IllegalArgumentException: Cannot resolve model: "fast"`（报错可读）。

---

## 6. application.yml 完整内容（替换）

**文件路径：`/Users/best/project/ai_platform/ai-agentscope/src/main/resources/application.yml`**

```yaml
server:
  port: 8081   # 避开 assistant-demo / ai-orchestrator-demo 的 8080

spring:
  application:
    name: ai-agentscope

# ★ 我们的模型规格表（不再是 agentscope.openai.* 那套）
ai:
  models:
    fast:                                        # ← 注册表 key，agent 里 .model("fast")
      base-url: ${AI_BASE_URL:http://ai-gateway.tz.com/v1}
      api-key: ${AI_API_KEY}                     # 不给默认值：缺失即在启动期失败
      model-name: ${AI_MODEL:gpt-4.1-mini}
    strong:                                      # 第二个模型：同一端点，另一个模型名
      base-url: ${AI_BASE_URL:http://ai-gateway.tz.com/v1}
      api-key: ${AI_API_KEY}
      model-name: ${AI_STRONG_MODEL:gpt-4.1}
```

**要知道的四件事**：

1. **没有 `agentscope.*` 段**。旧版的三把钥匙（`agentscope.model.provider` / `agentscope.openai.enabled` / `agentscope.agent.enabled`）随 starter 一起消失。
2. **`api-key` 不给默认值**——缺 `AI_API_KEY` 时 Spring 在启动期报 `Could not resolve placeholder 'AI_API_KEY'`，fail-fast。
3. **加一个模型 = yml 加一段，Java 代码一行不动**。这是 `Map` 形态的核心价值：注册表按 key 迭代，不用为每个模型写字段。
4. **生成参数的默认值用 `@DefaultValue` 标在 `ModelSpec` 的构造参数上**（0.2 / 2048 / false），yml 只在要覆盖时写。语义实测（第 48 章 §3.5）：注册表把参数烤进模型实例 → 调用方传空 options 时生效；调用方显式传值则**按字段覆盖**它。

---

## 7. 两个骨架类（新建）

> ⚠️ **修订说明（第四次）**：`ModelProperties` 回到 **record + `Map<String, ModelSpec>`** 形态。
> 中间那版 JavaBean（getter/setter + 具名字段）**已废弃**——它能自动生成 IDE 补全，但代码噪声太大，不值这个价。
> 代价与取舍见 §7.3。`GenerateDefaultsConfig` 仍不建（生成参数并入模型规格）。

### 7.1 ModelProperties —— 模型规格表（record + Map）

**路径：`.../ai_agentscope/model/ModelProperties.java`**

```java
package com.best.ai_agentscope.model;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模型规格表。
 *
 * yml 结构：ai.models.<name>.{base-url,api-key,model-name,temperature,max-tokens,parallel-tool-calls}
 *
 * 用 Map 而不是给每个模型写一个字段：加模型只动 yml，Java 代码零改动。
 * 代价是 IDE 不会为 ai.models.<name>.* 生成补全提示（原因见 §7.3），
 * 但【绑定与运行完全正常】—— 标黄只是 IDE 的静态检查。
 */
@ConfigurationProperties(prefix = "ai")
public record ModelProperties(Map<String, ModelSpec> models) {

    /**
     * 一个模型的规格。
     *
     * 三个生成参数用 @DefaultValue 标在构造参数上：yml 不写就用括号里的默认值，
     * 写了就地覆盖。实测：fast 不写 temperature → 0.2；strong 写 0.7 → 0.7。
     */
    public record ModelSpec(String baseUrl, String apiKey, String modelName,
                            @DefaultValue("0.2") Double temperature,
                            @DefaultValue("2048") Integer maxTokens,
                            @DefaultValue("false") Boolean parallelToolCalls) {
    }
}
```

**逐段讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| `@ConfigurationProperties(prefix = "ai")` + 字段名 `models` | 绑定路径 = **prefix + 字段名 + key**，所以 yml 是 `ai.models.<name>.*`。**这两处必须对齐**——写成 `prefix="ai.models"` + 字段 `specs` 就变成 `ai.models.specs.<name>.*`，yml 少一层时 Boot **不报错**、静默绑成空 map（症状：启动日志 `已注册模型：[]`） |
| `Map<String, ModelSpec>` | 加模型 = yml 加一段，Java 零改动。这是 Map 形态唯一但足够的理由 |
| `record` | 不可变 + 少 50 行样板。Spring Boot 3 原生支持 record 的构造器绑定 |
| `@DefaultValue("0.2")` 标在构造参数上 | 构造器绑定的标准默认值写法，比在紧凑构造器里写 `if (x == null) x = ...` 干净。**实测**：yml 不写就用它，写了就覆盖（fast→0.2，strong→0.7） |
| 校验**不在这里** | 校验放 `ModelRegistrar`（§7.2）——`ModelSpec` 只是个值对象，不知道「自己是哪个模型的规格」，报错带不上模型名 |

### 7.2 ModelRegistrar —— 注册表本体（对齐 ai-common 的 ModelRegister）

**路径：`.../ai_agentscope/model/ModelRegistrar.java`**

```java
package com.best.ai_agentscope.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型注册表（学习舱版 ModelRegister）。
 *
 * 把配置里的规格构造成 Model（含生成参数），注册进 AgentScope 的 ModelRegistry，
 * 之后任何地方 .model("fast") / .fallbackModel("strong") 按名取用。
 *
 * ⚠️ 时序：.model(String) 在 builder 配置时【立即解析】（实测），
 * 所以本 bean 必须先于所有 builder 配置创建 —— 靠 builder bean 的构造参数注入本类保序。
 */
@Slf4j
@Component
public class ModelRegistrar {

    private final Map<String, Model> registered = new LinkedHashMap<>();

    public ModelRegistrar(ModelProperties properties) {
        Map<String, ModelProperties.ModelSpec> specs = properties.models();
        if (specs == null || specs.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何模型规格 —— 检查 yml 的 ai.models.<name>.* 是否配了");
        }
        specs.forEach((name, spec) -> registered.put(name, build(name, spec)));
        registered.forEach(ModelRegistry::register);
        log.info("[model-registry] 已注册模型：{}", registered.keySet());
    }

    private Model build(String name, ModelProperties.ModelSpec spec) {
        // 必填三项校验放在这里：报错能带上「是哪个模型缺哪一项」
        require(name, "base-url", spec.baseUrl());
        require(name, "api-key", spec.apiKey());
        require(name, "model-name", spec.modelName());
        return OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .baseUrl(spec.baseUrl())
                .modelName(spec.modelName())
                .generateOptions(GenerateOptions.builder()
                        .temperature(spec.temperature())
                        .maxTokens(spec.maxTokens())
                        .parallelToolCalls(spec.parallelToolCalls())
                        .build())
                .build();
    }

    private static void require(String model, String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "模型 " + model + " 缺少 " + key + "（配 ai.models." + model + "." + key + "）");
        }
    }

    /** 本注册表里的模型名（ModelRegistry 没有枚举接口，名册自己留） */
    public Set<String> names() {
        return registered.keySet();
    }

    /** 按名取模型；没有返回 null（调用方给人话报错） */
    public Model byName(String name) {
        return registered.get(name);
    }
}
```

**五个设计点**：

| 点 | 为什么 |
|---|---|
| 构造器里做注册 | bean 创建即完成注册，生命周期最短；配合 47 号的参数注入保序 |
| 空名册抛异常 | 配置整段缺失时启动期就报，而不是等第一个 `.model("fast")` |
| **必填校验在这里** | 报错带模型名：`模型 fast 缺少 api-key（配 ai.models.fast.api-key）`——**实测过**，启动期抛出 |
| **生成参数在这里翻译** | 读默认值 / yml 覆盖值 → `GenerateOptions` → `builder.generateOptions(...)` |
| 自己维护 `registered` | `ModelRegistry` 只有 `resolve/canResolve`，**没有枚举接口**（javap 实测），枚举/展示要自己留名册 |
| `@Slf4j` + `log.info` | **不写 `System.out`**。启动日志带类名/级别/时间戳，可被日志配置收放（`INFO c.b.ai_agentscope.model.ModelRegistrar : [model-registry] 已注册模型：[fast, strong]`）。用 `{}` 占位符，不做字符串拼接 |

### 7.3 IDE 补全：可选项，不影响运行（实测）

你报的现象是「yml 里 `temperature` 不提示、还爆红」。**先把两件事分开**：

- **绑定 / 运行** —— `record + Map` 完全正常。实测启动日志：`[model-registry] 已注册模型：[fast, strong]`，参数照常下发。
- **IDE 提示** —— `ai.models.<name>.*` 没有补全、标黄。这是 **IDE 的静态检查**，不影响编译、打包、运行。

**根因**：Spring 的配置元数据处理器**不递归进 `Map` 的值类型**，只为 `ai.models` 生成 1 条元数据。所以 IDE 不知道 `ai.models.fast.temperature` 是什么。

⚠️ 注意：`@DefaultValue` 也**不改变**这一点（实测仍是 1 条）——它是给绑定器用的运行时默认值，不是给 IDE 的元数据。两者解决的是不同问题。

**三档选择（全部实测过）**：

| 配置类写法 | 元数据条数 | IDE 补全 | 代价 |
|---|---|---|---|
| **record + Map**（§7.1，本方案） | 1 | ❌ 标黄 | 无——加模型只动 yml |
| record + Map **+ additional metadata json** | 13 | ✅ 固定那几个 key | 加模型要同步改 json |
| JavaBean 具名字段（已废弃） | 12 | ✅ 全自动 | 每模型 6 字段 × getter/setter，噪声大 |

**验证元数据的 30 秒命令**（不用开 IDE）：

```bash
mvn -pl ai-agentscope clean compile
cat ai-agentscope/target/classes/META-INF/spring-configuration-metadata.json
```

数 `properties` 数组里有几条。**只有 1 条（`ai.models`）= IDE 一定标黄**。

**如果你要消除标黄**，加下面这个文件（可选，纯 IDE 体验）：

**路径：`ai-agentscope/src/main/resources/META-INF/additional-spring-configuration-metadata.json`**

```json
{
  "properties": [
    { "name": "ai.models.fast.base-url", "type": "java.lang.String" },
    { "name": "ai.models.fast.api-key", "type": "java.lang.String" },
    { "name": "ai.models.fast.model-name", "type": "java.lang.String" },
    { "name": "ai.models.fast.temperature", "type": "java.lang.Double" },
    { "name": "ai.models.fast.max-tokens", "type": "java.lang.Integer" },
    { "name": "ai.models.fast.parallel-tool-calls", "type": "java.lang.Boolean" },
    { "name": "ai.models.strong.base-url", "type": "java.lang.String" },
    { "name": "ai.models.strong.api-key", "type": "java.lang.String" },
    { "name": "ai.models.strong.model-name", "type": "java.lang.String" },
    { "name": "ai.models.strong.temperature", "type": "java.lang.Double" },
    { "name": "ai.models.strong.max-tokens", "type": "java.lang.Integer" },
    { "name": "ai.models.strong.parallel-tool-calls", "type": "java.lang.Boolean" }
  ]
}
```

⚠️ 它和 `record + Map` 是**互补**的：json 只补 IDE 提示，绑定仍由 `ModelProperties` 完成。加新模型时这里要同步加一段——**这是你要自己权衡的代价**。不加也完全能跑。

## 8. 启动类完整内容（替换）

**路径：`.../ai_agentscope/AgentscopeLabApplication.java`**

```java
package com.best.ai_agentscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan     // 扫描 @ConfigurationProperties（ModelProperties）
public class AgentscopeLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentscopeLabApplication.class, args);
    }
}
```

⚠️ `@ConfigurationPropertiesScan` 不能漏——漏了 `ModelProperties` 不会成为 bean，`ModelRegistrar` 的构造参数注入直接失败（`No qualifying bean of type ModelProperties`）。

---

## 9. 16 章索引

| 章 | 包 | 吃透的功能 | Spring 形态的产出 | 交付单 |
|---|---|---|---|---|
| 总纲 | — | 应用骨架 + 模型注册表 | pom / yml / 四个骨架类 / 启动类 | **46（本篇）** |
| 1 | `ch01_lifecycle` | HarnessAgent 生命周期 + RuntimeContext 身份 | Builder @Bean（`.model("fast")`）+ 第一个 Controller | **47** |
| 2 | `ch02_model` | 模型层：注册表 + 容错三件套 + 能力实测 | ModelProbeController + 实测表 | **48** |
| 3 | `ch03_message_event` | 消息与事件流 + 重建回复 | **SSE 端点**（Flux + `Flux.using`） | **49** |
| 4 | `ch04_tool` | 工具系统 + MCP | 工具 @Bean + 工具调用端点 | 50 |
| 5 | `ch05_permission` | 权限与 HITL | ASK 暂停 + 确认端点 | 51 |
| 6 | `ch06_context` | 上下文管理 + ContextManifest | contextSource @Bean + 排障端点 | 52 |
| 7 | `ch07_memory` | 双层记忆 | MemoryConfig @Bean | 53 |
| 8 | `ch08_workspace` | 工作区 + 多租户隔离 | 工作区初始化 + 按用户寻址 | 54 |
| 9 | `ch09_sandbox` | 文件系统与沙箱（需 Docker） | Sandbox spec @Bean | 55 |
| 10 | `ch10_plan_mode` | 计划模式 | Plan Mode 开关 + 管理端点 | 56 |
| 11 | `ch11_session` | 会话操作与恢复 | **AgentSession 管理器 bean** | 57 |
| 12 | `ch12_middleware` | Middleware | 中间件 @Bean 链 | 58 |
| 13 | `ch13_subagent` | 子 Agent | 子 agent 声明 + spawn 端点 | 59 |
| 14 | `ch14_skill` | Skill | 技能市场 bean | 60 |
| 15 | `ch15_channel` | Channel/Gateway | Channel bean + SSE 网关 | 61 |
| 16 | `capstone` | **毕业项目**：全功能组合 | 完整多 Agent 服务 | 62 |

---

## 10. 运行前的准备

```bash
export AI_API_KEY=你的端点密钥                  # 必须
export AI_BASE_URL=http://ai-gateway.tz.com/v1  # 可选（默认值已指向网关）
export AI_MODEL=gpt-4.1-mini                    # 可选，fast 的模型名
export AI_STRONG_MODEL=gpt-4.1                  # 可选，strong 的模型名（第 48 章容错实验用）
```

⚠️ `AI_MODEL` / `AI_STRONG_MODEL` 填什么取决于网关背后是哪家模型。第 48 章有实测表带你确认。

---

## 11. 运行手册与排障表

```bash
cd /Users/best/project/ai_platform
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
export AI_API_KEY=你的端点密钥

/opt/homebrew/bin/mvn -pl ai-agentscope spring-boot:run
```

**成功的第一眼标志**（启动日志里两行）：

```
[model-registry] 已注册模型：[strong, fast]
Started AgentscopeLabApplication in x.xxx seconds
```

| 现象 | 原因 | 修法 |
|---|---|---|
| `没有任何模型规格` 或 `Cannot resolve model: "fast"` | yml 段名/缩进不对，或没配 `ai.models.<name>.*` | 对照 §6 的 yml；**绑定路径 = prefix(`ai`) + 字段名(`models`) + key**，三处名字必须对齐（§7.1） |
| `Could not resolve placeholder 'AI_API_KEY'` | 密钥环境变量没设 | `export AI_API_KEY=...` |
| `模型规格缺少 api-key` | 占位符解析成了空串（变量设了但为空） | 同上 |
| `没有任何模型规格` | 配置整段缺失 | 检查 yml 缩进与段名 |
| `No qualifying bean of type ModelProperties` | 启动类漏了 `@ConfigurationPropertiesScan` | 补注解 |
| `Cannot resolve model: "strong"` | yml 里没配 `strong` | 按 §6 补上 |
| sysPrompt 中文乱码 | 模块没继承父 pom | 检查 pom 的 `<parent>` |
| **yml 里 `ai.models.<name>.*` 标黄 / 没有补全** | Spring 元数据处理器不递归 `Map` 值类型，只生成 1 条 | **不影响运行**。要消除就加 `additional-spring-configuration-metadata.json`（§7.3，可选） |

---

## 12. 已知风险与预案

| # | 风险 | 预案 |
|---|---|---|
| 1 | 端点背后模型能力未知（tool_choice / 结构化输出透传） | 第 48 章实测记录 |
| 2 | 第 9/10/14 章需 Docker | 装不了就跳过沙箱部分，其余不受影响 |
| 3 | 网关限流/计费 | 学习舱每章调用量都很小 |
| 4 | AgentScope 发新版 | 不单独升，要升全系列复核 |
| 5 | 注册表是**静态**的（`ModelRegistry` 全局） | 学习舱单应用无碍；将来多租户场景要评估（第 8 章会碰） |
| 6 | 模型不是 Spring bean | 注入 `Model` 类型会失败——**这是有意的**（见 48 号 §4②），取模型用字符串或 `registrar.byName()` |

---

## 13. 毕业项目预告（第 16 章 / 交付单 62）

一个「调研 → 改代码 → 评审」的多 Agent **Spring Boot 服务**：orchestrator（Plan Mode + todo + 会话恢复）
+ researcher / coder / reviewer 三个子 agent + 自定义工具与 MCP + 权限 HITL + MEMORY.md 记忆
+ Docker 沙箱 + SSE 前端 + OTel 观测。交付完整 yml + 全部源码 + 运行手册 + 验收清单。

---

## 本次修订的迁移清单（你已敲过的文件）

| 文件 | 动作 |
|---|---|
| 根 `pom.xml` | **不动**（上一版已改好） |
| `ai-agentscope/pom.xml` | **替换**为 §5（删 starter，加 `agentscope-extensions-model-openai` + `configuration-processor`） |
| `application.yml` | **替换**为 §6（删 `agentscope.*` 三把钥匙，换成 `ai.models.fast.*` / `ai.models.strong.*`） |
| `.../LabConfig.java` | **删除**（v1 SDK 形态遗留，早已标记待删） |
| `model/ModelProperties.java` | 新建（**含嵌套 `ModelSpec`**，JavaBean 风格 —— 元数据靠它） |
| `model/ModelRegistrar.java` | 新建（含必填校验 + 生成参数翻译） |
| ~~`model/ModelSpec.java`~~ | **不要单独建**——已并入 `ModelProperties` 的嵌套类 |
| ~~`model/GenerateDefaultsConfig.java`~~ | **不要建**——生成参数已并入模型规格（见 §7.4） |
| `AgentscopeLabApplication.java` | 替换为 §8（加 `@ConfigurationPropertiesScan`） |
| 47 号的 `AgentLabConfig.java` | 替换（见 47 号 §2） |

---

## 下一篇预告

**47 · 第 1 章 · HarnessAgent 生命周期与第一次调用**——Builder 提成 @Bean、`.model("fast")` 按名引用，两个 curl、同一会话，证明「新实例 ≠ 新会话」。
