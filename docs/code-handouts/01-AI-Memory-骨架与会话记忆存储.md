# 交付单 01 · AI-Memory 骨架与会话记忆存储

> **本篇目标**：建出 `AI-Memory` 模块并跑通编译，然后写出「会话记忆存储」的契约、实现与测试。
> **节奏**：两个检查点。每个检查点跑完命令、看到预期输出，再进下一节。

---

## 本篇在整体中的位置

```
AI-Common（你已完成）     模型路由 · 可观测
      ↑
AI-Memory  ← 本篇只做这一步的「会话记忆」半边
      ↑
assistant-*              后续
```

会话记忆负责回答一个问题：**这个会话刚才聊了什么。**
实现方式是给 LangChain4j 的 `MessageWindowChatMemory` 换一个我们自己写的存储后端。

本篇刻意**不引入 Redis**。先用内存实现，理由是：
① 不需要外部服务就能跑测试；② 它同时是后面 Redis 版的参照实现，你可以对着改。

---

## 你会敲出什么

| # | 文件 | 动作 |
|---|---|---|
| 1 | `pom.xml` | 改（加一个 module） |
| 2 | `ai-memory/pom.xml` | 新建 |
| 3 | `ai-memory/src/main/java/com/best/ai_memory/session/SessionMemoryStore.java` | 新建 |
| 4 | `ai-memory/src/main/java/com/best/ai_memory/session/InMemorySessionMemoryStore.java` | 新建（先空壳，后补实现） |
| 5 | `ai-memory/src/test/java/com/best/ai_memory/session/InMemorySessionMemoryStoreTest.java` | 新建 |

---

# 第一节 · 模块骨架

## 1.1 改 `pom.xml`（项目根目录）

把 `<modules>` 改成三行：

```xml
    <modules>
        <module>AI-Common</module>
        <module>assistant-demo</module>
        <module>AI-Memory</module>
    </modules>
```

> **为什么顺序无所谓**：Maven 不是按声明顺序构建的，它先读所有子模块的 pom 算出依赖图，再按拓扑顺序排。所以 `AI-Memory` 放中间还是放最后都一样。
>
> **但目录名要严格一致**：这里写 `AI-Memory`，那目录就必须叫 `AI-Memory`（大小写敏感，Linux 上不会给你容错）。

## 1.2 新建 `ai-memory/pom.xml`

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

    <artifactId>AI-Memory</artifactId>

    <dependencies>
        <!--
          AI-Memory 唯一的硬依赖。
          注意这里【不重复声明】langchain4j —— 它会从 AI-Common 传递过来。
          重复声明同一个 groupId:artifactId 而版本不同时，Maven 用"路径最近的版本"，
          结果就是你以为在用 A 版、其实在用 B 版。少写一行，少一个坑。
        -->
        <dependency>
            <groupId>com.best</groupId>
            <artifactId>AI-Common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!--
          测试依赖。JUnit 5 + AssertJ + Mockito 都在这一个 starter 里，
          版本由 spring-boot-starter-parent 统一管理，所以不写 <version>。
          scope=test 表示：只参与编译测试和跑测试，不会传递给引用 AI-Memory 的模块。
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

## ✅ 检查点 1

```bash
mvn -q -pl AI-Memory -am clean compile
```

预期：**没有输出**。`-q` 是 quiet，只在出错时打印；没有输出就是成功。

加 `-am`（also make）是因为 `AI-Memory` 依赖 `AI-Common`，第一次构建需要它先装进本地仓库。之后单独跑可以去掉。

想确认它真的做了事：

```bash
ls ai-memory/target/classes
```

现在应该是空的（还没写 Java 文件）。第二节写完再看。

---

# 第二节 · 会话记忆存储

这一节我们用**红绿顺序**写：先建空壳 → 写测试 → 看它红 → 补实现 → 看它绿。

这个顺序看着绕，但它是唯一能让你确认"测试真的在测东西"的办法。直接写完实现再补测试，你永远不知道测试是真的通过了，还是压根没被执行到。

## 2.1 契约：`SessionMemoryStore`

**路径**：`ai-memory/src/main/java/com/best/ai_memory/session/SessionMemoryStore.java`

```java
package com.best.ai_memory.session;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 平台自己的「会话记忆存储」契约。
 *
 * <p>为什么要在 LangChain4j 的 ChatMemoryStore 之上再包一层？
 * <ul>
 *   <li>注入时有明确的类型。以后项目里可能出现别的 ChatMemoryStore 实现，
 *       按接口注入时才不会撞车</li>
 *   <li>给平台特有的方法留位置。比如以后要「列出某个用户的全部会话」，
 *       LangChain4j 的接口没这个能力，我们在这里加</li>
 *   <li>和你在 AI-Common 里用 IEndpoint / ModelProperties 定义契约是同一个思路 ——
 *       对外暴露自己的类型，不直接暴露第三方库的类型</li>
 * </ul>
 *
 * <p>它本身不新增任何方法，只是给 LangChain4j 的接口起了个平台的名字。
 */
public interface SessionMemoryStore extends ChatMemoryStore {
}
```

不认识 `ChatMemoryStore` 的三个方法？它们是：

| 方法 | 签名 | 什么时候被调 |
|---|---|---|
| `getMessages` | `List<ChatMessage> getMessages(Object memoryId)` | 每次要读历史时 |
| `updateMessages` | `void updateMessages(Object memoryId, List<ChatMessage> messages)` | 内存窗口裁剪后写回时 |
| `deleteMessages` | `void deleteMessages(Object memoryId)` | 会话结束时（需要你显式调） |

`memoryId` 的类型是 `Object` 而不是 `String` —— 这是 LangChain4j 的设计，允许你用任意对象做会话标识。我们用 `String` 存，但签名必须跟着它。

## 2.2 空壳实现：`InMemorySessionMemoryStore`

**路径**：`ai-memory/src/main/java/com/best/ai_memory/session/InMemorySessionMemoryStore.java`

**先只写方法签名，方法体全部抛异常**：

```java
package com.best.ai_memory.session;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 基于进程内存的会话记忆存储。
 *
 * <p>适用场景：本地开发、单元测试。
 * <b>不能用于生产</b> —— 进程一重启，所有对话历史就没了，
 * 而且多副本部署时每个 Pod 看到的历史都不一样。
 */
public class InMemorySessionMemoryStore implements SessionMemoryStore {

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        throw new UnsupportedOperationException("还没实现");
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        throw new UnsupportedOperationException("还没实现");
    }

    @Override
    public void deleteMessages(Object memoryId) {
        throw new UnsupportedOperationException("还没实现");
    }
}
```

## 2.3 写测试

**路径**：`ai-memory/src/test/java/com/best/ai_memory/session/InMemorySessionMemoryStoreTest.java`

```java
package com.best.ai_memory.session;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这五个用例不只是"验证功能对不对"，
 * 其中两个（getReturnsCopy / updateStoresCopy）是在【固化设计决策】——
 * 它们一旦变红，说明有人改了"要不要拷贝"这个决定，必须回来看注释。
 */
class InMemorySessionMemoryStoreTest {

    private final InMemorySessionMemoryStore store = new InMemorySessionMemoryStore();

    @Test
    @DisplayName("没存过的会话返回空列表，而不是 null —— 否则每个调用方都要判空")
    void unknownSessionReturnsEmptyList() {
        assertThat(store.getMessages("not-exists")).isEmpty();
    }

    @Test
    @DisplayName("写入之后能原样读出来")
    void writeThenRead() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("我叫熊鑫"),
                AiMessage.from("你好熊鑫"));

        store.updateMessages("s1", messages);

        assertThat(store.getMessages("s1")).containsExactlyElementsOf(messages);
    }

    @Test
    @DisplayName("两个会话互不干扰")
    void sessionsAreIsolated() {
        store.updateMessages("s1", List.of(UserMessage.from("会话一的第 1 句")));

        assertThat(store.getMessages("s2")).isEmpty();
    }

    @Test
    @DisplayName("getMessages 返回的是副本：就地改它不会影响存储")
    void getReturnsCopy() {
        store.updateMessages("s1", List.of(UserMessage.from("abc")));

        List<ChatMessage> got = store.getMessages("s1");
        got.add(UserMessage.from("这条不该被存进去"));

        assertThat(store.getMessages("s1")).hasSize(1);
    }

    @Test
    @DisplayName("updateMessages 存的是副本：写入之后再改原 List 不会污染存储")
    void updateStoresCopy() {
        List<ChatMessage> mutable = new ArrayList<>(List.of(UserMessage.from("abc")));

        store.updateMessages("s1", mutable);
        mutable.add(UserMessage.from("这条不该被存进去"));

        assertThat(store.getMessages("s1")).hasSize(1);
    }

    @Test
    @DisplayName("删除之后会话就没了")
    void deleteRemovesSession() {
        store.updateMessages("s1", List.of(UserMessage.from("abc")));

        store.deleteMessages("s1");

        assertThat(store.getMessages("s1")).isEmpty();
    }
}
```

### 先跑一遍，确认它是红的

```bash
mvn -pl AI-Memory test
```

预期：**6 个测试全部失败**，报错都是 `UnsupportedOperationException: 还没实现`。

这一步不能跳。如果这里就绿了，说明测试根本没被执行到（比如测试类没被扫到、或者方法名不匹配），你后面会一直被骗。

## 2.4 补实现

把 `InMemorySessionMemoryStore.java` 替换成：

```java
package com.best.ai_memory.session;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于进程内存的会话记忆存储。
 *
 * <p>适用场景：本地开发、单元测试。
 * <b>不能用于生产</b> —— 进程一重启，所有对话历史就没了，
 * 而且多副本部署时每个 Pod 看到的历史都不一样。
 */
public class InMemorySessionMemoryStore implements SessionMemoryStore {

    /**
     * memoryId → 消息列表。
     *
     * <p>key 用 Object 而不是 String：LangChain4j 的接口签名就是 Object，
     * 我们用 String 做实际类型，但泛型上必须跟着它，否则覆盖不了方法。
     *
     * <p>用 ConcurrentHashMap 而不是 HashMap：ChatMemory 会被多个请求线程
     * 同时读写（同一个用户连发两条消息就是并发），普通 HashMap 在扩容时
     * 可能让读线程拿到 null 或死循环。
     */
    private final Map<Object, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> stored = store.get(memoryId);

        // 没存过返回空列表，不返回 null —— 让调用方少一次判空
        if (stored == null) {
            return new ArrayList<>();
        }

        // 两个细节都要注意，缺一不可：
        //
        // ① 必须是【可变】的副本，不能用 List.copyOf(...)。
        //    LangChain4j 拿到这个列表之后是直接 add(...) 修改它的，
        //    然后才回调 updateMessages。返回不可变集合会直接抛
        //    UnsupportedOperationException。
        //
        // ② 必须返回【副本】而不是内部那个引用。
        //    如果返回内部引用，LangChain4j 会就地改坏我们的数据；
        //    而且并发读的线程会看到"改了一半"的列表。
        return new ArrayList<>(stored);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 同样要拷贝。理由和上面第 ② 点相同，只是方向反过来：
        // LangChain4j 可能复用传进来的那个 List 实例，后面还会改它。
        // 直接存引用的话，它下次 add 就顺手改掉了我们存的内容。
        store.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId);
    }
}
```

### 再跑一遍，确认它是绿的

```bash
mvn -pl AI-Memory test
```

预期：

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## ✅ 检查点 2

```bash
ls ai-memory/target/classes/com/best/ai_memory/session
```

应该能看到两个 `.class` 文件。测试报告在 `ai-memory/target/surefire-reports/`。

---

# 自查清单

敲完之后逐条确认：

- [ ] 父 `pom.xml` 的 `<modules>` 有三行，第三行是 `AI-Memory`
- [ ] `mvn -q -pl AI-Memory -am clean compile` 无输出
- [ ] 先跑测试时看到 **6 个红**，且报错是 `UnsupportedOperationException: 还没实现`
- [ ] 补完实现后 **6 个绿**
- [ ] 你能不看注释，说出 `getMessages` 为什么要返回 `new ArrayList<>(stored)` 而不是 `List.copyOf(stored)`

最后一条是关键。**如果你答不上来，就回去把那段注释再读一遍** —— 这个"可变副本"的坑，LangChain4j 官方自己的 `InMemoryChatMemoryStore` 都没处理干净（它直接返回内部引用），后面换成 Redis 版时会再遇到一次。

---

# 下一篇预告

**交付单 02 · 摘要机制**：窗口滑出去的消息怎么压缩进摘要。

在写它之前，先想一个问题（不用回我，自己想想）：

> `ChatMemoryStore` 只有 `getMessages` 和 `updateMessages`。当窗口把最老的那条消息挤出去的时候，**我们怎么知道被挤掉的是哪几条？**

这个问题的答案决定了摘要逻辑写在哪一层。下一篇会从这个问题开始。
