package com.best.ai_graph.state;

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

    public static final String TOOL_CALL_ID = "toolCallId";
    public static final String LAST_TOOL_SIGNATURE = "lastToolSignature";
    public static final String REPEAT_COUNT = "repeatCount";
    public static final String STEP_COUNT = "stepCount";
    public static final String FORCE_END = "forceEnd";
    public static final String EXPERT_ERROR = "expertError";
    public static final String ROUTE_TRACE = "routeTrace";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(MESSAGES, Channels.<ChatMessage>appender(ArrayList::new)),
            Map.entry(USER_ID, Channels.base(() -> "")),
            Map.entry(SESSION_ID, Channels.base(() -> "")),
            Map.entry(NEXT, Channels.base(() -> "")),
            Map.entry(MEMORY_CONTEXT, Channels.base(() -> "")),
            Map.entry(APPROVED, Channels.base(() -> false)),
            Map.entry(PROPOSED_TOOL, Channels.base(() -> "")),
            Map.entry(PROPOSED_ARGS, Channels.base(() -> "")),
            Map.entry(PROPOSED_BY, Channels.base(() -> "")),
            Map.entry(APPROVAL_DECIDED, Channels.base(() -> false)),
            Map.entry(TOOL_CALL_ID, Channels.base(() -> "")),
            Map.entry(LAST_TOOL_SIGNATURE, Channels.base(() -> "")),
            Map.entry(REPEAT_COUNT, Channels.base(() -> 0)),
            Map.entry(STEP_COUNT, Channels.base(() -> 0)),
            Map.entry(FORCE_END, Channels.base(() -> false)),
            Map.entry(EXPERT_ERROR, Channels.base(() -> false)),
            Map.entry(ROUTE_TRACE, Channels.base(() -> ""))
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

    public String userId()           { return this.<String>value(USER_ID).orElse(""); }
    public String sessionId()        { return this.<String>value(SESSION_ID).orElse(""); }
    public String next()             { return this.<String>value(NEXT).orElse(""); }
    public String memoryContext()    { return this.<String>value(MEMORY_CONTEXT).orElse(""); }
    public boolean approved()        { return this.<Boolean>value(APPROVED).orElse(false); }
    public String proposedTool()     { return this.<String>value(PROPOSED_TOOL).orElse(""); }
    public String proposedArgs()     { return this.<String>value(PROPOSED_ARGS).orElse("{}"); }
    public String proposedBy()       { return this.<String>value(PROPOSED_BY).orElse(""); }
    public boolean approvalDecided() { return this.<Boolean>value(APPROVAL_DECIDED).orElse(false); }

    public String toolCallId()        { return this.<String>value(TOOL_CALL_ID).orElse(""); }
    public String lastToolSignature() { return this.<String>value(LAST_TOOL_SIGNATURE).orElse(""); }
    public int repeatCount()          { return this.<Integer>value(REPEAT_COUNT).orElse(0); }
    public int stepCount()            { return this.<Integer>value(STEP_COUNT).orElse(0); }
    public boolean forceEnd()         { return this.<Boolean>value(FORCE_END).orElse(false); }
    public boolean expertError()      { return this.<Boolean>value(EXPERT_ERROR).orElse(false); }
    public String routeTrace()        { return this.<String>value(ROUTE_TRACE).orElse(""); }
}
