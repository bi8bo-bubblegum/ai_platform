package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.core.AgentRole;
import com.best.ai_orchestrator.role.RoleRules;

/**
 * 这个应用有哪些专家 —— <b>唯一事实来源</b>。
 *
 * <p>三个字段分开，因为它们回答三个不同的问题，合并就是"名字骗人"：
 * <ul>
 *   <li>{@code key} —— <b>规划器选人的键</b>。Planner 输出这个字符串，编排器据此找角色</li>
 *   <li>{@code description} —— 给<b>规划器</b>看的"什么时候派给它"（别人的视角）</li>
 *   <li>{@code rules} —— 给<b>执行者</b>看的"注意什么"（它自己的视角）。
 *       这两者不能复用同一个字符串</li>
 *   <li>{@code mcpClientKey} —— <b>它能看到/能调哪些工具</b>。
 *       = ai.mcp.servers 的 key = 网关分发的 token 授权范围。
 *       越权在网关层就被拦掉，这里不需要再维护一张映射表</li>
 * </ul>
 *
 * <p>★ <b>加一个专家 = 加一个枚举值 + 一个 {@link RoleRules} 实现</b>，
 * 节点、提示词组装、Map 注册全自动 —— 没有"漏改一处静默失效"的空间
 * （这正是 34 号把接线收进循环的原因）。
 */
public enum Roles {

    ORDER("order",
            "订单相关：查订单、订单状态、行程信息、金额、开票情况",
            "order",
            new OrderRules()),

    ANALYSIS("analysis",
            "数据分析相关：需要查数据库、做统计、跑 SQL 得出数据结论",
            "analysis",
            new AnalysisRules()),

    KNOWLEDGE("knowledge",
            "知识库相关：检索文档、查资料、找已有方案或说明",
            "knowledge",
            new KnowledgeRules()),

    /**
     * 纯对话专家的样子（示例，默认不启用）：
     * {@code mcpClientKey} 传 {@code null} → 提示词里写"本次没有可用工具"，
     * 模型直接给结论。没有 token 要配，也不走工具审批。
     */
    //    CHAT("chat", "闲聊、澄清需求、解释概念，不需要查任何数据", null, new ChatRules()),
    ;

    private final String key;
    private final String description;
    private final String mcpClientKey;
    private final RoleRules rules;

    Roles(String key, String description, String mcpClientKey, RoleRules rules) {
        this.key = key;
        this.description = description;
        this.mcpClientKey = mcpClientKey;
        this.rules = rules;
    }

    /** 规划器选人的键，也是 {@code Map<String, AgentRole>} 的 key */
    public String key() {
        return key;
    }

    public AgentRole toAgentRole() {
        return new AgentRole(key, description, rules.rules(), mcpClientKey);
    }
}
