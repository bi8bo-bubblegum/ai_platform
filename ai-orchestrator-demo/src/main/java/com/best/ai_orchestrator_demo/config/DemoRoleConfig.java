package com.best.ai_orchestrator_demo.config;

import com.best.ai_common.properties.McpProperties;
import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator_demo.role.Roles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link Roles} 枚举注册成编排器要的角色表。
 *
 * <p>★ <b>这是整个应用里唯一一处"把业务枚举接进框架"的地方</b>：
 * 框架只认 {@code Map<String, AgentRole>}，不知道 Roles 的存在；
 * 业务也只认 Roles，不碰框架内部。<b>依赖方向是业务 → 框架，不是框架 → 业务。</b>
 *
 * <p><b>为什么启动时校验 MCP 通道存在</b>：角色声明了 {@code mcp-client-key: order}，
 * 而 yml 里没有配 {@code ai.mcp.servers.order} 时 ——
 * 不校验的症状是运行期一条 warn + "这个角色没有工具"，模型开始瞎猜；
 * 校验后是<b>启动失败 + 一句人话</b>。fail-fast 把静默失效变成显式错误。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class DemoRoleConfig {

    @Bean
    public Map<String, AgentRole> agentRoles(McpProperties mcpProperties) {
        Map<String, AgentRole> roles = new LinkedHashMap<>();
        for (Roles r : Roles.values()) {
            roles.put(r.key(), r.toAgentRole());
        }

        // ---- 校验：用到的 MCP 通道必须配过（纯对话角色除外，它没有通道） ----
        for (AgentRole role : roles.values()) {
            String channel = role.mcpClientKey();
            if (channel == null) {
                continue;   // 纯对话角色，没有工具可调
            }
            if (!mcpProperties.getServers().containsKey(channel)) {
                throw new IllegalStateException("角色 " + role.name()
                        + " 声明的 MCP 通道 " + channel + " 没有配置 —— "
                        + "请在 ai.mcp.servers 下补上 " + channel
                        + "（它的值 = 网关分发的 token 授权范围）");
            }
        }

        log.info("角色数={} → {}", roles.size(), roles.keySet());
        return roles;
    }
}
