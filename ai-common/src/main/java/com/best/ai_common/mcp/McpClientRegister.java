package com.best.ai_common.mcp;

import com.best.ai_common.properties.McpProperties;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class McpClientRegister implements AutoCloseable{

    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpClientRegister(McpProperties properties){
        properties.getServers().forEach((name, server) -> {
            if(!server.isEnabled()){
                log.info("MCP服务已禁用,跳过{}", name);
                return;
            }
            try {
                clients.put(name, create(name, server, properties.getToolTimeout()));
                log.info("MCP 服务端已连接: name={}, transport={}", name, server.getTransport());
            } catch (Exception e){
                log.error("MCP 服务端连接失败，已跳过: name={}, transport={}", name, server.getTransport(), e);
            }
        });
        log.info("MCP 客户端初始化完成，成功 {} 个: {}", clients.size(), clients.keySet());
    }

    private McpClient create(String name, McpProperties.Server server, Duration defaultToolTimeout) {
        McpTransport transport = buildTransport(server);
        DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder()
                .key(name)
                .transport(transport)
                .clientName("ai-platform")
                .clientVersion("1.0")
                .toolExecutionTimeout(server.getToolTimeout() != null ? server.getToolTimeout() : defaultToolTimeout);
        if(server.getTimeout() != null){
            builder.initializationTimeout(server.getTimeout());
        }
        return builder.build();
    }

    private McpTransport buildTransport(McpProperties.Server server) {
        return switch (server.getTransport()){
            case STDIO -> {
                if(server.getCommand() == null || server.getCommand().isEmpty()){
                    throw new IllegalArgumentException("stdio 传输必须配置 command");
                }
                StdioMcpTransport.Builder b = new StdioMcpTransport.Builder()
                        .command(server.getCommand())
                        .logEvents(false);
                if(server.getEnvironment() != null && !server.getEnvironment().isEmpty()){
                    b.environment(server.getEnvironment());
                }
                yield b.build();
            }
            case HTTP -> {
                if (server.getUrl() == null || server.getUrl().isBlank()){
                    throw new IllegalArgumentException("http 传输必须配置 url");
                }
                StreamableHttpMcpTransport.Builder b = new StreamableHttpMcpTransport.Builder()
                        .url(server.getUrl());
                if(server.getHeaders() != null && !server.getHeaders().isEmpty()){
                    b.customHeaders(server.getHeaders());
                }
                if(server.getTimeout() != null){
                    b.timeout(server.getTimeout());
                }
                yield b.build();
            }
        };
    }

    public McpClient get(String name){
        return clients.get(name);
    }

    public List<McpClient> all(){
        return List.copyOf(clients.values());
    }

    public boolean isEmpty(){
        return clients.isEmpty();
    }

    @Override
    public void close() throws Exception {
        clients.forEach((name, client) -> {
            try {
                client.close();
            } catch (Exception e){
                log.warn("关闭 MCP 客户端失败: {}", name, e);
            }
        });
        clients.clear();
    }
}
