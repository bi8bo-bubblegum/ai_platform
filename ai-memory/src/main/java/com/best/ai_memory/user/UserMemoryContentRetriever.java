package com.best.ai_memory.user;

import com.best.ai_memory.session.SessionKey;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Slf4j
@RequiredArgsConstructor
public class UserMemoryContentRetriever implements ContentRetriever {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static final String HEADER =
            "[关于这位用户的已知信息，按时间倒序。若其中互相冲突，以靠前的为准]\n";

    private final MemoryRecallService recallService;

    @Override
    public List<Content> retrieve(Query query) {
        String userId = userIdOf(query);
        if (userId == null) {
            return List.of();
        }

        List<UserMemory> memories = recallService.recall(userId, query.text());
        if (memories.isEmpty()) {
            return List.of();
        }

        return List.of(Content.from(TextSegment.from(render(memories))));
    }

    private String userIdOf(Query query) {
        Object memoryId = query.metadata() == null ? null : query.metadata().chatMemoryId();
        if (memoryId == null) {
            return null;
        }
        try {
            return SessionKey.parse(String.valueOf(memoryId)).userId();
        } catch (IllegalArgumentException e) {
            // memoryId 格式不对说明装配错了。这里只 warn 不抛：
            // 抛出去会让整次对话失败，而"没有长期记忆"只是一个降级，
            // 不该把能正常回答的对话变成 500。
            log.warn("memoryId 格式不符合 userId::sessionId，本次跳过长期记忆检索: {}", memoryId);
            return null;
        }
    }

    private String render(List<UserMemory> memories) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (UserMemory m : memories) {
            sb.append("- ")
                    .append(DATE.format(m.getCreatedAt()))
                    .append(' ')
                    .append(m.getContent())
                    .append('\n');
        }
        return sb.toString();
    }
}