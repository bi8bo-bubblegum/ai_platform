package com.best.ai_common.mcp;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public enum ToolRisk {

    LOW,
    MEDIUM,
    HIGH;

    public static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static ToolRisk parse(String raw){
        if(raw == null || raw.isBlank()){
            return warnOnce("<空>", "risk 为空");
        }
        return switch (raw.trim().toLowerCase()){
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high" -> HIGH;
            default -> warnOnce(raw, "未知risk值");
        };
    }

    private static ToolRisk warnOnce(String raw, String reason){
        if(WARNED.add(raw)){
            log.warn("{}: [{}] -> 按最严等级HIGH处理。" + "如果这是新增等级，请在 ToolRisk.parse 里补映射", reason, raw);
        }
        return HIGH;
    }

    public boolean atLeast(ToolRisk threshold){
        return this.ordinal() >= threshold.ordinal();
    }
}
