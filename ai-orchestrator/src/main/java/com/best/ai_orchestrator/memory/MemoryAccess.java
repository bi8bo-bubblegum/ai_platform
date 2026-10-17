package com.best.ai_orchestrator.memory;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_memory.session.SessionKey;
import com.best.ai_memory.user.MemoryRecallService;
import com.best.ai_memory.user.UserMemory;
import com.best.ai_memory.user.UserMemoryRecorder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆的唯一出入口 —— <b>本模块与 ai-memory 之间的那一层薄适配</b>。
 *
 * <p><b>为什么要有这个类</b>：ai-memory 提供的是三个独立的 Bean
 * （{@code ChatMemoryProvider} / {@code MemoryRecallService} / {@code UserMemoryRecorder}），
 * 而编排器需要的是三句人话："刚才聊了什么" / "用户是谁" / "记住这次"。
 * 把三句话收在一个类里，编排器的构造函数就不会变成一长串记忆 Bean。
 *
 * <p>★ <b>它也把两个容易漏的责任收成了代码结构</b>：
 * <ol>
 *   <li><b>memoryId 不手拼</b> —— 统一走 {@link SessionKey#of}。
 *       手拼少一个 {@code ::} 的后果本项目踩过：解析在同步段抛异常，
 *       被 catch 成一条 warn，表现成"长期记忆永远写不进去但服务照跑"。</li>
 *   <li><b>失败一律降级不抛</b> —— 记忆是增强项，不是主流程。
 *       让一次召回失败把整轮编排打断，是错误的取舍。</li>
 * </ol>
 *
 * <p><b>会话历史和长期记忆是两件事，不要混：</b>
 * <table border="1">
 *   <tr><th></th><th>会话历史</th><th>长期记忆</th></tr>
 *   <tr><td>回答的问题</td><td>这次对话刚才聊了什么</td><td>用户是谁</td></tr>
 *   <tr><td>给谁看</td><td>Planner + Synthesizer</td><td>所有人（含 Worker）</td></tr>
 *   <tr><td>存储</td><td>Redis（TTL 滑动）</td><td>MySQL 事实表</td></tr>
 * </table>
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryAccess {

    private final ChatMemoryProvider chatMemoryProvider;
    private final MemoryRecallService recallService;
    private final UserMemoryRecorder recorder;

    /** 带进 Planner / Synthesizer 的对话历史最多取多少条 */
    private final int conversationTurns;

    /**
     * @return 渲染后的会话历史（不含本次请求）。读不到就返回空串 —— <b>不抛</b>
     */
    public String conversation(String memoryId) {
        try {
            ChatMemory memory = chatMemoryProvider.get(memoryId);
            if (memory == null) {
                return "";
            }
            List<ChatMessage> all = memory.messages();
            if (all == null || all.isEmpty()) {
                return "";
            }
            int from = Math.max(0, all.size() - Math.max(1, conversationTurns));
            return ChatMessageTexts.render(new ArrayList<>(all.subList(from, all.size())));
        } catch (Exception e) {
            log.warn("会话历史读取失败，按空处理。memoryId={}", memoryId, e);
            return "";
        }
    }

    /**
     * @return 渲染后的长期记忆文本。读不到就返回空串 —— <b>不抛</b>
     */
    public String longTerm(String userId, String query) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        try {
            List<UserMemory> facts = recallService.recall(userId, query);
            if (facts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("[关于这位用户的已知信息，按时间倒序，若冲突以靠前的为准]\n");
            for (UserMemory m : facts) {
                sb.append("- ").append(m.getContent()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("长期记忆召回失败，本次跳过。userId={}", userId, e);
            return "";
        }
    }

    /**
     * 触发一次<b>异步</b>的长期记忆抽取。
     *
     * <p><b>本方法不阻塞</b>：重的活（LLM 抽取 + 向量化 + 落库）在线程池里跑。
     *
     * <p>★ 传 {@link List#copyOf} 而不是原列表：调用方持有的可能是活引用
     * （比如某个 appender 通道的值），异步线程和主线程同时改它会抛
     * {@code ConcurrentModificationException} —— 而那个异常只打在异步线程的日志里。
     */
    public void remember(String memoryId, List<ChatMessage> turns) {
        try {
            recorder.recordAsync(memoryId, List.copyOf(turns));
        } catch (Exception e) {
            log.warn("长期记忆写入触发失败，memoryId={}", memoryId, e);
        }
    }

    /**
     * 把这一轮"用户问 + 系统答"追加进会话历史。
     *
     * <p>★ <b>由编排器在收敛时调用，而不是留给 Controller</b> ——
     * 理由和 {@link #remember} 相同："别忘了存"是人的纪律，
     * 收进代码结构才不会漏。漏了的症状是"第二轮对话没有上下文"，
     * 而那种问题在单轮测试里完全看不出来。
     *
     * <p><b>暂停（等人审批）时不调用</b> —— 那一轮还没有答案，不该写进历史。
     */
    public void appendExchange(String memoryId, String userMessage, String aiMessage) {
        try {
            ChatMemory memory = chatMemoryProvider.get(memoryId);
            if (memory == null) {
                return;
            }
            memory.add(UserMessage.from(userMessage));
            memory.add(AiMessage.from(aiMessage));
        } catch (Exception e) {
            log.warn("会话历史写入失败，memoryId={}", memoryId, e);
        }
    }

    /** memoryId 的唯一构造点。★ 不手拼，见类注释 */
    public static String memoryIdOf(String assistantId, String userId, String sessionId) {
        return SessionKey.of(assistantId, userId, sessionId).toMemoryId();
    }
}
