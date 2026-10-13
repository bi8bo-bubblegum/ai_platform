package com.best.ai_graph.agent;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

public interface ExpertAgent {

    ExpertProposal propose(@UserMessage String turnInstruction);

    record ExpertProposal(
            @Description("给用户的处理意见或结论，用中文回答，回答简洁")
            String reply,

            @Description("要调用的工具名，必须从上面列出的工具清单中选；不需要工具时填NONE")
            String tool,

            @Description("工具调用参数，JSON对象；不需要调用工具填空对象")
            Map<String, Object> arguments
    ){}
}
