# probes —— 一次性验证脚本

这里放**为了回答某个具体技术问题而写的独立小脚本**。
它们不是单元测试（项目没有测试基础设施），而是**"这个机制到底行不行"的实证**。

**它们的作用不是"跑起来"，是"留下来"。** 半年后有人再提同一个方案，
跑一遍就有答案，不用重新论证一遍。

---

## SubGraphProbe.java —— 子图内部的中断会不会传播到父图？

### 它回答的问题

第 3 步本来打算"把每个 expert 做成子图，ReAct 循环放在子图内部"。
这个方案有个未知：**子图里的 `InterruptibleAction` 中断时，父图能不能感知到？**

如果感知不到，那么"子图内部的审批"就是废的 —— 用户批不了，
Controller 也检测不到"需要审批"。

### 结论：**不能。所以子图方案被否决。**

实测输出（2026-10-12）：

```
=== 开始 ===
[父图] entry 执行
  [子图] subStart 执行
  [子图] gate.interrupt() 被调用 → 返回【有值】(要求停下)

★ 父图正常返回: [entry, sub-start]
★ 结论: 子图中断【没有】传到父图 —— 父图当它是普通节点跑完
```

三条事实拆开看：

1. `gate.interrupt()` **被调用了** → 子图内部的中断判断是生效的 ✓
2. `gate.apply()` **没有执行** → 中断成功拦住了节点 ✓
3. **父图 `invoke()` 正常返回** → 既不抛 `GraphInterruptException`，也没执行 `after`，
   而是**静默返回了一个残缺状态**（log 里只有 `entry, sub-start`）

**第 3 条是最坏的形态**：`AgentController` 里那个
`catch → GraphInterruptException.of(e).isPresent()` 永远命中不了，
于是会走到 `respond()` 返回 `{"status":"done"}` ——
**前端看到"完成"，实际工具没执行、后续流程没跑、审批再也触发不了，而且没有任何报错。**

### 怎么跑

项目没有装 `mysql` 那种"一条命令就跑"的便利，langgraph4j 也不是全局可用的 CLI，
所以要手工拼 classpath：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home

CP=$(find ~/.m2/repository/org/bsc -path "*1.9.2*" -name "*.jar" ! -name "*sources*" | tr '\n' ':')
CP="$CP$HOME/.m2/repository/org/bsc/async/async-generator/5.0.0/async-generator-5.0.0.jar:"
CP="$CP$(find ~/.m2/repository/org/slf4j -name "*.jar" ! -name "*sources*" | tr '\n' ':')"
CP="$CP$(find ~/.m2/repository/com/fasterxml/jackson/core -name "*.jar" ! -name "*sources*" | tr '\n' ':')"

$JAVA_HOME/bin/java -cp "$CP" docs/probes/SubGraphProbe.java
```

> Java 11+ 的**单文件源码模式**：`java Foo.java` 会自动编译再运行，不用先 `javac`。

### 三个踩过的坑（下次别再踩）

1. **`-path "*1.9.2*"` 不能省。**
   本机 `.m2` 里有 langgraph4j-core 的 **1.8.10 / 1.9.2 / 1.9.3** 三个版本。
   不加限定的话 `find` 会把它们全塞进 classpath，**顺序决定用哪个**，
   而 1.8.x 的 `InterruptionMetadata.Builder` 没有 `reason(String)` 方法 →
   报一个看起来完全不相关的编译错"找不到符号: 方法 reason(String)"。

2. **`org.bsc.async` 是独立 artifact。**
   `AsyncGenerator$Cancellable` 在 `org.bsc.async:async-generator` 里，
   **不在 `org.bsc.langgraph4j` 下**。漏了报 `NoClassDefFoundError`。

3. **`mvn -pl <单模块> dependency:build-classpath` 会失败。**
   因为它要解析兄弟模块（`ai-common` / `ai-memory`）的依赖，而那些还没安装到本地仓库。
   所以宁可手工拼 —— 反正这个脚本只需要 langgraph4j。

### 相关文档

- `docs/code-handouts/30-multiagent工具接入-按危险等级中断.md`（第 6 节列了这个待验证点）
- `docs/code-handouts/32-multiagent循环收进专家-扁平化ReAct.md`（改用条件边实现循环）
