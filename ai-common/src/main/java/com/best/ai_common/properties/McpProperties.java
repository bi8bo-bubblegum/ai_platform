package com.best.ai_common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "ai.mcp")
@Data
public class McpProperties{
    private boolean enabled = false;
    private Duration toolTimeout = Duration.ofSeconds(60);
    private Map<String, Server> servers = new LinkedHashMap<>();
    private Duration riskCacheTtl = Duration.ofSeconds(60);
    public enum Transport{
        STDIO,
        HTTP
    }

    @Data
    public static class Server{
        private Transport transport = Transport.STDIO;
        private boolean enabled = true;
        private List<String> command;
        private Map<String, String> environment;
        private String url;
        private Map<String, String> headers;
        private Duration timeout;
        private Duration toolTimeout;
    }
}
