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

    public static final String PROPOSED_TOOL = "proposedTool";
    public static final String PROPOSED_ARGS = "proposedArgs";
    public static final String PROPOSED_BY = "proposedBy";
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

    /**
     * Constructs an AgentState with the given initial data.
     *
     * @param initData the initial data for the agent state
     */
    public MultiAgentState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> messages(){
        return this.<List<ChatMessage>>value(MESSAGES).orElseGet(List::of);
    }

    public String userId()          { return this.<String>value(USER_ID).orElse(""); }
    public String sessionId() {return this.<String>value(SESSION_ID).orElse("");}
    public String next()            { return this.<String>value(NEXT).orElse(""); }
    public String memoryContext()   { return this.<String>value(MEMORY_CONTEXT).orElse(""); }
    public boolean approved()       { return this.<Boolean>value(APPROVED).orElse(false); }
    public String proposedTool()    { return this.<String>value(PROPOSED_TOOL).orElse(""); }
    public String proposedArgs()    { return this.<String>value(PROPOSED_ARGS).orElse("{}"); }
    public String proposedBy()      { return this.<String>value(PROPOSED_BY).orElse(""); }
    public boolean approvalDecided(){ return this.<Boolean>value(APPROVAL_DECIDED).orElse(false); }
}
