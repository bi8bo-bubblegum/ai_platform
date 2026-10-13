package com.best.multiagent.config;

import com.best.ai_common.message.ChatMessageTexts;
import com.best.ai_common.register.ChatModelRegister;
import com.best.ai_graph.graph.MultiAgentGraphFactory;
import com.best.ai_graph.state.MultiAgentState;
import com.best.multiagent.agent.SupervisorAgent;
import dev.langchain4j.service.AiServices;
import org.bsc.langgraph4j.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MultiAgentGraphConfig {

    @Bean
    public SupervisorAgent supervisorAgent(ChatModelRegister chatModelRegister){
        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModelRegister.getDefault())
                .build();
    }

    @Bean
    public CompiledGraph<MultiAgentState> multiAgentGraph(
            SupervisorAgent supervisorAgent,
            MultiAgentGraphFactory graphFactory
    ) throws GraphStateException {
        return graphFactory.createAgent(
                SupervisorAgent.Routing.class,
                messages -> supervisorAgent.route(ChatMessageTexts.render(messages)),
                SupervisorAgent.Routing.FINISH.edgeKey()
        );
    }
}
