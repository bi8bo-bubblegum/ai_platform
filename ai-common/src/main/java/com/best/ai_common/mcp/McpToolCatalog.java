package com.best.ai_common.mcp;

import com.best.ai_common.mcp.db.McpToolEntity;
import com.best.ai_common.mcp.db.McpToolMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class McpToolCatalog {

    private final McpClientRegister register;
    private final McpToolMapper toolMapper;
    private final Duration cacheTtl;

    private volatile Map<String, ToolRisk> riskCache = Map.of();

    private volatile long riskLoadedAt = 0L;

    public McpToolCatalog(McpClientRegister register, McpToolMapper toolMapper, Duration cacheTtl) {
        this.register = register;
        this.toolMapper = toolMapper;
        this.cacheTtl = cacheTtl;
    }

    public List<McpTool> list(String clientKey){
        McpClient client = register.get(clientKey);
        if(client == null){
            log.warn("没有配置名为{}的mcp客户端，该专家本次无可用工具, 检查ai.mcp.servers里有没有这个key", clientKey);
            return List.of();
        }
        Map<String, ToolRisk> risks = riskSnapshot();
        try {
            return client.listTools().stream()
                    .map(spec -> toTool(spec, risks))
                    .toList();
        } catch (Exception e){
            log.error("拉取工具清单失败 client={}，该专家本次无可用工具", clientKey, e);
            return List.of();
        }
    }

    private McpTool toTool(ToolSpecification spec, Map<String, ToolRisk> risks){
        ToolRisk risk = risks.getOrDefault(spec.name(), ToolRisk.HIGH);
        return new McpTool(spec.name(), spec.description(), spec.parameters(), risk);
    }

    private Map<String, ToolRisk> riskSnapshot(){
        long now = System.currentTimeMillis();
        Map<String, ToolRisk> cached = riskCache;
        if(!cached.isEmpty() && now - riskLoadedAt < cacheTtl.toMillis()){
            return cached;
        }
        try {
            Map<String, ToolRisk> loaded = toolMapper.selectList(null).stream()
                    .filter(e -> e.getEffectiveName() != null)
                    .collect(Collectors.toMap(
                            McpToolEntity::getEffectiveName,
                            e -> ToolRisk.parse(e.getRisk()),
                            (a, b) -> a
                    ));
            riskCache = loaded;
            riskLoadedAt = now;
            log.info("工具风险等级已加载：{}条", loaded.size());
            return loaded;
        } catch (Exception e){
            log.error("读取 tools 表失败，本次沿用上一次的等级快照（{} 条）", cached.size(), e);
            return cached;
        }
    }

    public ToolRisk riskOf(String toolName){
        if(toolName == null || toolName.isBlank()){
            return ToolRisk.HIGH;
        }
        return riskSnapshot().getOrDefault(toolName, ToolRisk.HIGH);
    }
}
