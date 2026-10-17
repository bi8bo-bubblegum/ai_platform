package com.best.ai_orchestrator.core;

/**
 * 一个「角色」的定义。
 *
 * <p><b>它同时声明三件事，而且这三件事必须一致</b>：
 * 身份（谁）、能力边界（能碰到哪些工具）、行为准则（怎么做）。
 * 一个"数据分析专家"如果手里握着订单工具，它的准则和工具清单就会打架 ——
 * 所以它们放在同一条记录里，不可能漂移。
 *
 * @param name         角色名。会填进提示词的"你是谁"，也是规划器选人的键
 * @param description  给<b>规划器</b>看的"这个角色能干什么"（决定什么时候派给它）。
 *                     ★ 它和 {@code systemPrompt} 是两件事：前者是"别人怎么看它"，
 *                     后者是"它怎么看自己"，不能复用同一个字符串
 * @param systemPrompt 这个角色的<b>工作准则</b>。只写它独有的判断，
 *                     通用输出格式由 {@code WorkerAgent} 的骨架保证，不要重复
 * @param mcpClientKey MCP 客户端 key。★ <b>它决定了这个角色能看到哪些工具</b> ——
 *                     越权在网关那一层就被拦掉了，这里不需要再维护一张映射表
 */
public record AgentRole(String name, String description, String systemPrompt, String mcpClientKey) {
}
