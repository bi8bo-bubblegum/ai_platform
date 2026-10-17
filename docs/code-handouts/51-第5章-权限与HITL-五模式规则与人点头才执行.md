# 51 · 第 5 章 · 权限与 HITL：五模式、规则与「人点头才执行」

> **本章吃透一个功能：权限系统 + Human-in-the-Loop。**
> 上一章工具已经能跑了，这一章回答「**什么时候该让模型动手，什么时候必须先问人**」。
>
> 对应官方文档页：building-blocks/permission-system
> 前置：50 号的 `LabTools` + `Toolkit` bean 已就位（本章直接复用）

---

## 1. 你会敲出什么

| 文件 | 动作 |
|---|---|
| `ai-agentscope/src/main/java/com/best/ai_agentscope/ch05_permission/PermissionProbeController.java` | 新建 |
| `probe/PermissionProbe.java`（可选，§12） | 新建 |

⚠️ **先说清本章边界**：本章把「判定」完全实测（不依赖模型、不依赖 API key），
把「HITL 事件协议」讲到字节码级确认的形态；**「确认结果怎么回传给挂起的 agent」这一条本章不实测**，
原因与第 11 章（会话恢复）合并说明，见 §13。这是诚实的技术债，不是遗漏。

---

## 2. 四个行为，不是三个

`PermissionBehavior` 有**四个**值（javap 实测，文档说"三态"是漏的）：

| 行为 | 含义 |
|---|---|
| `ALLOW` | 放行 |
| `DENY` | 拒绝 |
| `ASK` | 需要问人 → HITL |
| `PASSTHROUGH` | **本层不表态，继续往下走** |

`PASSTHROUGH` 是理解整条链路的关键：它让「规则层」和「模式兜底层」可以串联——
规则没命中就 `PASSTHROUGH`，把决定权交给下一层，而不是默认拒绝。

---

## 3. 实测：五模式矩阵

`PermissionMode` 五个值，对「只读工具」和「非只读工具」的默认判定（**实测数据，§12 探针可复现**）：

| 模式 | `echo`（只读） | `get_weather`（非只读） |
|---|---|---|
| `DEFAULT` | ASK | ASK |
| `ACCEPT_EDITS` | ALLOW | ASK |
| `EXPLORE` | ALLOW | **DENY** |
| `BYPASS` | ALLOW | ALLOW |
| `DONT_ASK` | **DENY** | **DENY** |

**三个反直觉的点，逐个记**：

1. **`DONT_ASK` 是「全拒」而不是「免问」**。它的拒绝理由（字节码里的字符串）是 `User is not available to answer permission prompts` ——语义是「没有人可以回答问题，所以什么都不放行」。**别把它当"跳过确认"用**，那是 `BYPASS`。
2. **`EXPLORE` 对非只读工具是硬 DENY**，不是 ASK。它对应「只调研不改动」的场景。
3. **`DEFAULT` 下只读工具也要 ASK**。想「只读免问」得用 `ACCEPT_EDITS` 或 `EXPLORE`。

---

## 4. 判定链路：四层，顺序固定

反编译 `PermissionEngine.checkPermission` 得到的真实顺序：

```
① checkDenyRules(tool, input)       命中 → 立即 DENY（含 withSuggestedRules 之前的短路）
        ↓ 未命中（PASSTHROUGH）
② checkAskRules(tool, input)        命中 → ASK + generateSuggestions(tool, input)
        ↓ 未命中
③ toolCheckPermissions(tool, input) 工具自身声明（ToolBase.checkPermissions）
        ↓ 返回空
④ 模式兜底（switchIfEmpty）          按 §3 的矩阵给 ALLOW / DENY / ASK
```

**两个由字节码支撑的结论**：

- **deny 优先于一切**，包括 `BYPASS` 模式（实测：`BYPASS + deny(boom)` → `DENY`）。
- **链路里没有 `checkAllowRules`**。`addAllowRule(...)` 存了规则，但引擎判定**不读它**（反编译确认 checkPermission 只调 deny/ask 两层）。

**那 allow 规则是干什么的？** 从数据流看：`PermissionDecision.getSuggestedRules()` 会带上 `source=suggested` 的 ALLOW 规则（实测 ④）——它的用途是「用户点了同意后，把这条同意**记录成 allow 规则**持久化，下次免问」。所以 allow 规则是**HITL 的产物/缓存**，不是配置项。想预置免问，用模式（`ACCEPT_EDITS` / `BYPASS`）或 ask 规则。

---

## 5. 规则的三个坑（全是实测）

### 坑一：bucket key 必须是 `toolName`

```java
// ✅ 对
.addDenyRule("get_weather", new PermissionRule("get_weather", null, DENY, "manual"))

// ❌ 错：第一参不是 "safety" 之类的分类名
.addDenyRule("safety", new PermissionRule("get_weather", null, DENY, "manual"))
```

反编译证据：`checkDenyRules` 里是 `rulesFor(denyRules, tool.getName())` —— 按**工具名**取规则列表。第一参数传别的，规则永远查不到。

### 坑二：`ruleContent` 只能填 `null`

实测矩阵（`BYPASS` 模式下加 deny 规则，看能否压过 ALLOW）：

| `ruleContent` | 结果 |
|---|---|
| `null` | **DENY** ✅ |
| `"*"` | ALLOW ❌ |
| `"get_weather"` | ALLOW ❌ |
| `"get_weather(city:北京)"` | ALLOW ❌ |
| `"Bash(rm:*)"` | ALLOW ❌ |

**只有 `null` 能命中**。原因是 `ruleMatches()` 直接调 `tool.matchRule(ruleContent, input)`，而 `ToolBase.matchRule` 的**整个实现就是 `return ruleContent == null`**（javap -c 实测，方法体三条字节码）：

```java
public boolean matchRule(String ruleContent, Map<String, Object> input) {
    return ruleContent == null;   // ← 真的就只有这一行
}
```

**这意味着 2.0.4 的规则粒度只能到「整个工具」，做不到「按参数值」**（比如"只允许查北京"）。这是框架未完成的部分，不是你不会配。想要参数级控制，第 12 章（Middleware）自己写。

### 坑三：`ToolBase.matchRule` 名字骗人

它叫 matchRule 但不做任何匹配（见上）。**别在业务代码里调它做判断**——你写 `tool.matchRule("get_weather", input)` 期望 true，实际永远 false（非 null）。

---

## 6. HITL 事件协议

ASK 命中后，框架不自己弹窗，而是往事件流里发一个事件，等外部回传结果。三个类（javap 实测）：

```java
// 框架 → 你：要求确认
new RequireUserConfirmEvent(replyId, agentId, List<ToolUseBlock> toolCalls)
// 你 → 框架：确认结果
new UserConfirmResultEvent(replyId, agentId, List<ConfirmResult> results)
// 单个工具的确认
new ConfirmResult(boolean confirmed, ToolUseBlock toolCall, List<PermissionRule> rules, String reason)
```

要点：

- **`ConfirmResult.rules`** 就是「用户同意了，把这条记成规则」——对应 §4 说的 allow 规则用途。传进去下次免问。
- **`RequireUserConfirmEvent` 可以一次带多个 `ToolUseBlock`**（批量确认），不是逐个问。
- **拒绝的后果是 `ToolResultState.DENIED`**（`ToolResultState` 五态之一），工具根本不执行。

**Spring 里的对接形状**（第 3 章的 SSE 事件流直接复用）：

```
前端收到 REQUIRE_USER_CONFIRM 事件 → 渲染确认卡片
用户点同意/拒绝 → POST /agent/confirm
后端构造 ConfirmResult → 回传给挂起的 agent
```

⚠️ 最后一步「回传给挂起的 agent」的 API **本章不实测**（见 §13）。前两步的协议是确凿的。

---

## 7. 挂起机制：`ToolResultBlock.suspended()`

第 4 章实测过：工具抛 `ToolSuspendException` → 结果是 `state=RUNNING` + `isSuspended=true` + `metadata={agentscope_suspended=true}`。

这套机制就是为 HITL 准备的：**工具执行到一半发现需要人确认，主动挂起，把现场留在 `metadata` 里**，等人回话再恢复。

框架还提供了两个静态工厂：

```java
ToolResultBlock.suspended(toolUseBlock);                        // 无原因
ToolResultBlock.suspended(toolUseBlock, toolSuspendException);  // 带原因
```

`ToolSuspendException.getReason()` 就是挂起原因（实测回文本 `等人确认`）。

---

## 8. Spring 形态代码

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/ch05_permission/PermissionProbeController.java`**

```java
package com.best.ai_agentscope.ch05_permission;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 第 5 章：把「五模式 × 工具」判定矩阵跑成一张 REST 可查的表 */
@Slf4j
@RestController
public class PermissionProbeController {

    private final Toolkit toolkit;

    public PermissionProbeController(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    @GetMapping("/permission/matrix")
    public Map<String, Object> matrix() {
        List<ToolBase> tools = toolkit.getToolNames().stream()
                .map(n -> (ToolBase) toolkit.getTool(n))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        for (PermissionMode mode : PermissionMode.values()) {
            PermissionEngine engine = new PermissionEngine(
                    PermissionContextState.builder().mode(mode).build());
            Map<String, Object> row = new LinkedHashMap<>();
            for (ToolBase t : tools) {
                PermissionDecision d = engine.checkPermission(t, Map.of()).block();
                row.put(t.getName(), d.getBehavior().name());
            }
            result.put(mode.name(), row);
        }
        return result;
    }
}
```

**逐行讲为什么**：

| 代码 | 为什么这样写 |
|---|---|
| `(ToolBase) toolkit.getTool(n)` 强转 | `getTool` 返回接口 `AgentTool`，而 `checkPermissions` / `getName` 的实现在抽象类 `ToolBase` 上。本章工具全是注解注册的（`ReflectiveFunctionTool extends ToolBase`），强转安全 |
| 每个 mode **新建一个** `PermissionEngine` | `PermissionEngine` 构造时持有 `PermissionContextState`，不是无状态的；复用会把上一个 mode 的规则带过来 |
| `checkPermission(t, Map.of())` 传空 input | 本章只验模式兜底层，不挂规则；input 空也能跑 |
| 矩阵直接返回 `Map` | 这是**探针端点**，不是业务 API——生产要做成"当前用户的实际权限视图"，得带上真实 `PermissionContextState` |

**真实的 `PermissionContextState` 从哪来**：`HarnessAgent.Builder.permissionContext(state)`（javap 确认）。生产形态是从 yml 读默认模式 + 从数据库读用户偏好，启动期或每请求组装：

```java
PermissionContextState state = PermissionContextState.builder()
        .mode(PermissionMode.DEFAULT)
        .addDenyRule("boom", new PermissionRule("boom", null, PermissionBehavior.DENY, "policy"))
        .build();
```

---

## 9. 运行与检查点

```bash
mvn -pl ai-agentscope spring-boot:run
curl -s localhost:8081/permission/matrix | python3 -m json.tool
```

**期望输出（与 §3 的表一一对应）**：

```json
{
  "DEFAULT":       { "get_weather": "ASK",  "boom": "ASK",  "echo": "ASK"  },
  "ACCEPT_EDITS":  { "get_weather": "ASK",  "boom": "ASK",  "echo": "ALLOW" },
  "EXPLORE":       { "get_weather": "DENY", "boom": "DENY", "echo": "ALLOW" },
  "BYPASS":        { "get_weather": "ALLOW","boom": "ALLOW","echo": "ALLOW" },
  "DONT_ASK":      { "get_weather": "DENY", "boom": "DENY", "echo": "DENY"  }
}
```

**检查点（硬判据）**：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 直接打 `/permission/matrix` | 五行五列，值与上表完全一致 |
| 2 | 把 `echo` 的 `readOnly` 改成 `false` 再打 | `EXPLORE` 行的 `echo` 从 `ALLOW` 变 `DENY` |
| 3 | 给 `matrix()` 的 builder 加 `.addDenyRule("boom", new PermissionRule("boom", null, DENY, "manual"))` 再打 | `BYPASS` 行的 `boom` 从 `ALLOW` 变 `DENY`（证明 deny 压过模式） |
| 4 | 把上一条的 `null` 改成 `"*"` 再打 | **又变回 `ALLOW`**（证明坑二：非 null 不命中） |
| 5 | 加 `.addAllowRule("boom", ...)` 再打 | **没有任何变化**（证明 allow 不参与判定） |

第 3、4、5 条是本章最值钱的三个实验——它们证明的不是"我会配规则"，而是"**我知道这套规则系统在 2.0.4 的真实能力边界**"。

---

## 10. 容易写错 / 被改坏的 6 个点

1. **`DONT_ASK` 当"跳过确认"用** —— 它是全拒。要免问用 `BYPASS`（并接受它同时放过危险工具）。
2. **规则第一参传分类名**（`"safety"` / `"tool"` / `"file"`）——必须是 `toolName`，否则静默失效。
3. **`ruleContent` 写匹配表达式** —— 只能 `null`。写了不报错，但不生效（最难排查的那种）。
4. **指望 allow 规则预置免问** —— 引擎不读 allow 规则。它是 HITL 的产物。
5. **复用 `PermissionEngine` 实例** —— 它持有 context，跨 mode/跨请求复用会串。
6. **在生产里把 `userId` 当信任输入用来选 mode** —— 和 47 号同一个坑：本章矩阵端点没有身份概念，生产必须来自认证上下文。

---

## 11. 自查清单

- [ ] `/permission/matrix` 五行与 §3 表完全一致
- [ ] 检查点 3、4、5 三个实验都做过，且结果符合预期
- [ ] 能解释「为什么 deny 能压过 BYPASS，allow 却不能压过 deny」
- [ ] 知道 `ToolResultState` 五态分别出现在什么场景（第 4 章 + 本章）
- [ ] 能在白板上画出 §4 的四层链路

---

## 12. 探针代码（可选，不起服务）

**路径：`ai-agentscope/src/main/java/com/best/ai_agentscope/probe/PermissionProbe.java`**

```java
package com.best.ai_agentscope.probe;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;

public class PermissionProbe {

    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ToolProbe.LabTools());
        ToolBase echo = (ToolBase) toolkit.getTool("echo");
        ToolBase weather = (ToolBase) toolkit.getTool("get_weather");
        ToolBase boom = (ToolBase) toolkit.getTool("boom");

        System.out.println("=== 五模式矩阵 ===");
        for (PermissionMode mode : PermissionMode.values()) {
            PermissionEngine e = new PermissionEngine(
                    PermissionContextState.builder().mode(mode).build());
            System.out.printf("  %-13s echo=%-7s weather=%s%n", mode,
                    e.checkPermission(echo, Map.of("text", "hi")).block().getBehavior(),
                    e.checkPermission(weather, Map.of("city", "北京")).block().getBehavior());
        }

        System.out.println("=== ruleContent 只能是 null（BYPASS 下加 deny）===");
        for (String rc : new String[]{null, "*", "get_weather"}) {
            PermissionEngine en = new PermissionEngine(PermissionContextState.builder()
                    .mode(PermissionMode.BYPASS)
                    .addDenyRule("get_weather",
                            new PermissionRule("get_weather", rc, io.agentscope.core.permission.PermissionBehavior.DENY, "manual"))
                    .build());
            System.out.printf("  ruleContent=%-14s weather=%s%n",
                    rc == null ? "null" : "\"" + rc + "\"",
                    en.checkPermission(weather, Map.of("city", "北京")).block().getBehavior());
        }

        System.out.println("=== matchRule 名不副实 ===");
        System.out.println("  matchRule(null) = " + weather.matchRule(null, Map.of()));
        System.out.println("  matchRule(\"*\")  = " + weather.matchRule("*", Map.of()));
    }
}
```

```bash
mvn -pl ai-agentscope compile exec:java -Dexec.mainClass=com.best.ai_agentscope.probe.PermissionProbe
```

---

## 13. 本章没实测的部分（诚实记账）

**「确认结果怎么回传给挂起的 agent」没有跑通**。已知事实与未知边界：

| 已知（javap / 反编译） | 未知 |
|---|---|
| `RequireUserConfirmEvent` / `UserConfirmResultEvent` / `ConfirmResult` 三个类的构造与字段 | 回传的**入口方法**是哪个 |
| `HarnessAgent.Builder.permissionContext(state)` 挂载点 | `HarnessGateway.run(...)` 的 `InboundMessage` 只承载 `List<Msg>`，不承载确认 |
| `ToolSuspendException` + `agentscope_suspended` 挂起机制 | 疑似走 `CallableAgent.call(List<Msg>, JsonNode)` 的 JsonNode 参数，**未验证** |
| `AgentSession` 这个类**在 2.0.4 里不存在**（官方文档的 session-log 页写的 `submit/interrupt/resume/respond` 找不到对应类） | 文档与构件的对应关系 |

**为什么留到第 11 章**：确认回传与会话恢复（`checkpoint` / 断点续跑）共用同一套「把外部输入喂回挂起态」的机制，分开研究会重复造两遍探针。第 11 章会用假端点 + 真挂起流程一次打通，届时本章的协议部分要按实测结果修订。

**在此之前，别在生产里依赖 ASK 规则**——判定层是实的，恢复层还没验。

---

## 14. 下一篇预告

**52 · 第 6 章 · 上下文管理** —— 长任务三件套的第一件：`contextSource` / 预算 / `ContextManifest` 排障 / 结构化压缩 / 工具结果 offload。第 4 章的 toolkit 会在这里第一次挂到 `HarnessAgent` 上。
