package com.best.ai_agentscope.ch04_tool;

import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolkitConfig {

    @Bean
    public Toolkit labToolkit(){
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LabTools());
        return toolkit;
    }
}
