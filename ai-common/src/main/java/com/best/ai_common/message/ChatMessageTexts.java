package com.best.ai_common.message;

import dev.langchain4j.data.message.*;

import java.util.List;

public final class ChatMessageTexts {
    private ChatMessageTexts(){}

    public static String roleOf(ChatMessage message){
        if(message == null){
            return "其他";
        }
        if(message instanceof UserMessage){
            return "用户";
        }
        if(message instanceof AiMessage){
            return "助手";
        }
        if(message instanceof SystemMessage){
            return "系统";
        }
        if(message instanceof ToolExecutionResultMessage){
            return "工具";
        }
        return "其他";
    }

    public static String textOf(ChatMessage message){
        if(message == null) {
            return "";
        }
        try {
            if(message instanceof UserMessage um){
                return orEmpty(um.singleText());
            }
            if(message instanceof AiMessage am){
                return orEmpty(am.text());
            }
            if(message instanceof SystemMessage sm){
                return orEmpty(sm.text());
            }
            if(message instanceof ToolExecutionResultMessage tm){
                return orEmpty(tm.text());
            }
        } catch (Exception e){
            return "";
        }
        return "";
    }

    public static String render(List<ChatMessage> messages){
        return render(messages, false);
    }

    public static String renderConversation(List<ChatMessage> messages){
        return render(messages, true);
    }

    private static String render(List<ChatMessage> messages, boolean conversationOnly){
        if(messages == null || messages.isEmpty()){
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if(conversationOnly && !(message instanceof UserMessage) && !(message instanceof AiMessage)){
                continue;
            }
            String text = textOf(message);
            if(text.isBlank()){
                continue;
            }
            sb.append(roleOf(message)).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    public static String lastUserText(List<ChatMessage> messages){
        if(messages == null || messages.isEmpty()){
            return "";
        }
        for(int i = messages.size() - 1; i >= 0; i--){
            if(messages.get(i) instanceof UserMessage um) {
                return textOf(um);
            }
        }
        return "";
    }

    private static String orEmpty(String s){
        return s == null ? "" : s;
    }
}
