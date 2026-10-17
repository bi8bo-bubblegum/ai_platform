package com.best.ai_agentscope.ch01_lifecycle;

import com.best.ai_agentscope.model.ModelRegistrar;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class AgentLabConfig {

    @Bean
    public HarnessAgent.Builder noteTakerBuilder(ModelRegistrar registrar){
        Model model = registrar.getModel("fast");
        return HarnessAgent.builder()
                .name("note-taker")
                .agentId("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手")
                .model(model)
                .workspace(Path.of(".agentscope/workspace"));
    }
}
