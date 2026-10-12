package com.best.ai_memory.session;

public record SessionKey(String assistantId, String userId, String sessionId) {

    private static final String SEPARATOR = "::";

    public String toMemoryId(){
        return assistantId+ SEPARATOR + userId + SEPARATOR + sessionId;
    }

    public SessionKey{
        requireNoSeparator(assistantId, "assistantId");
        requireNoSeparator(userId, "userId");
        if(sessionId == null || sessionId.isBlank()){
            throw new IllegalArgumentException("sessionId 不能为空");
        }
    }

    public static SessionKey parse(String memoryId){
        if(memoryId == null){
            throw new IllegalArgumentException("memoryId 不能为 null");
        }
        int first = memoryId.indexOf(SEPARATOR);
        int second = first < 0 ? -1 : memoryId.indexOf(SEPARATOR, first + SEPARATOR.length());
        if(second < 0){
            throw new IllegalArgumentException(
                    "memoryId 格式必须是 assistantId" + SEPARATOR + "userId" + SEPARATOR
                            + "sessionId，实际收到: " + memoryId);
        }
        return new SessionKey(
                memoryId.substring(0, first),
                memoryId.substring(first + SEPARATOR.length(), second),
                memoryId.substring(second + SEPARATOR.length())
        );

    }

    public static SessionKey of(String assistantId, String userId, String sessionId){
        return new SessionKey(assistantId, userId, sessionId);
    }

    private static void requireNoSeparator(String value, String name){
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    name + " 不能包含 " + SEPARATOR + "，否则 memoryId 无法解析，实际: " + value);
        }
    }
}
