package com.best.ai_graph.config;

import com.best.ai_common.mcp.ToolRisk;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.graph")
public class GraphProperties {

    private String assistantId;

    private String approvalMinRisk = "high";

    private long checkpointTtlHours = 24;

    private int maxSteps = 8;

    private int maxRepeat = 3;

    private int maxHistoryMessages = 20;

    private int maxToolResultChars = 4000;

    private int recursionLimit = 50;

    public ToolRisk approvalMinRisk(){
        return ToolRisk.parse(approvalMinRisk);
    }
}
