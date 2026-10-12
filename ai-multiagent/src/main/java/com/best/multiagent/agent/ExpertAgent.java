package com.best.multiagent.agent;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.Map;

public interface ExpertAgent {

    @SystemMessage("""
            你是 {{role}} 专家。
            
            ## 你可以调用的工具
            {{tools}}
            
            ## 已知的用户信息（可能为空）：
            {{memory}}
            
            关于工具：
             - 需要调用工具时，在 tool 字段填工具名、arguments 字段填参数对象
             - 不需要调用工具时，tool 填 NONE、arguments 填空对象
             - 一次只提议一个工具；看到它的返回结果后，再决定是否需要下一个
             - 只能从上面的工具清单里选，不要编造工具名
             - 不要假装已经执行了 —— 真正的执行由后续环节完成
            """)
    ExpertProposal propose(@V("role") String role, @V("tools") String tools, @V("memory") String memory, @UserMessage String request);

    record ExpertProposal(
            @Description("给用户的处理意见或结论，用中文，简洁")
            String reply,

            @Description("要调用的工具名，必须从给定的工具清单里选；不需要调用工具时填 NONE")
            String tool,

            @Description("工具调用参数，JSON 对象；不需要调用工具填空对象")
            Map<String, Object> arguments
    ){}
}
