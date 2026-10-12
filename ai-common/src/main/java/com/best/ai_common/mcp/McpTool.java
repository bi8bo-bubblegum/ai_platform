package com.best.ai_common.mcp;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public record McpTool(
        String name,
        String description,
        JsonObjectSchema parameters,
        ToolRisk risk
) {
}
