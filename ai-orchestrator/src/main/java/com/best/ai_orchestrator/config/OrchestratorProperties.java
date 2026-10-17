package com.best.ai_orchestrator.config;

import com.best.ai_orchestrator.core.AgentRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排器的配置项。
 *
 * <p>用属性类而不是散落的 {@code @Value}：默认值写在字段上、
 * 一眼能看全有哪些可调项、从 yml 到对象的路径是显式的。
 */
@Data
@ConfigurationProperties(prefix = "ai.orchestrator")
public class OrchestratorProperties {

    /** 暂停状态的 TTL（小时）。<b>暂停状态不是资产</b> —— 配 TTL 避免无限堆积 */
    private long runTtlHours = 24;

    /**
     * 这张图的标识。<b>它是双重标识，重复会静默串号</b>：
     * ① 暂停状态的 key 命名空间；② 长期记忆表的 {@code source_assistant}。
     *
     * <p>留空则取 {@code spring.application.name} —— 服务名唯一时这项可以不配。
     */
    private String assistantId;

    /** 达到或超过这个危险等级的工具，需要人工批准后才执行。不认识的值按 HIGH 处理 */
    private String approvalMinRisk = "high";

    /** 主循环最大轮数。<b>它是刹车，不是目的地</b> */
    private int maxRounds = 6;

    /** 连续多少轮"已完成子任务数没有增加"就收敛 —— 增量式终止判据 */
    private int noProgressLimit = 2;

    /** 同一批最多并行几个子任务。<b>只影响并行度，不影响正确性</b> */
    private int maxParallel = 3;

    /** 单次运行的 token 预算上限。<b>超了就强制收敛，不是报错</b> */
    private int tokenBudget = 200_000;

    /** 单个子任务的 token 上限 */
    private int perTaskTokenBudget = 30_000;

    /** 默认的工具轮次上限（子任务自己可以覆盖） */
    private int maxToolRounds = 5;

    /** 带进 Planner / Synthesizer 的对话历史最多取多少条 */
    private int conversationTurns = 10;
}
