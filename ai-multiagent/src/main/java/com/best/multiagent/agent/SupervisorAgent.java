package com.best.multiagent.agent;


import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    enum Routing{
        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER,

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS,

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE,

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH;

        public String edgeKey(){
            return name().toLowerCase();
        }
    }

    @SystemMessage("""
            你是任务调度器。根据对话内容，判断下一步该交给谁。

            判断依据：如果最近的对话里已经有明确的结论或回答，就选 FINISH。
            """)
    Routing route(@UserMessage String conversation);
}
