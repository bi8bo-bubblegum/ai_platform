package com.best.multiagent.agent;


import com.best.ai_graph.expert.Expert;
import com.best.ai_graph.graph.ExpertRouting;
import com.best.multiagent.expert.AnalysisExpert;
import com.best.multiagent.expert.KnowledgeExpert;
import com.best.multiagent.expert.OrderExpert;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SupervisorAgent {

    enum Routing implements ExpertRouting {
        @Description("订单相关：查订单、订单状态、行程信息、金额、开票情况")
        ORDER("订单助手", new OrderExpert()),

        @Description("数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论")
        ANALYSIS("数据分析助手", new AnalysisExpert()),

        @Description("知识库相关：检索文档、查资料、找已有方案或说明")
        KNOWLEDGE("知识库助手", new KnowledgeExpert()),

        @Description("任务已经完成，不需要再派发 —— 最近几轮里已经有明确的结论或回答")
        FINISH(null, null);

        private final String role;
        private final Expert expert;

        Routing(String role, Expert expert) {
            this.role = role;
            this.expert = expert;
        }

        public String edgeKey(){
            return name().toLowerCase();
        }

        @Override
        public String expertRole() {
            return role;
        }

        @Override
        public Expert expert() {
            return expert;
        }
    }

    @SystemMessage("""
            你是任务调度器。根据对话内容，判断下一步该交给谁。
            
            判断依据：
            - 结合语意和对话历史选择合适的助手
            - 如果最近的对话里已经有明确的结论或回答，就选 FINISH
            """)
    Routing route(@UserMessage String conversation);
}
