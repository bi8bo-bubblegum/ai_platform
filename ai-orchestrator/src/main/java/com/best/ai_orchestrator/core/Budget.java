package com.best.ai_orchestrator.core;

import lombok.Getter;
import lombok.Setter;

/**
 * Token 预算 —— <b>终止条件的最后一道兜底</b>。
 *
 * <p>多智能体的成本是单次对话的十几倍，所以"花多少"必须是<b>一等公民</b>，
 * 而不是跑完再看账单。这个类只做三件事：记账、判超、给降级信号。
 *
 * <p><b>它算的是估算值，不是精确值。</b> 精确用量在 {@code ai_token_usage} 台账里
 * （由 SDK 层的监听器写）。两者<b>不能混在一张表里</b> ——
 * 估算值的存在意义是"及时刹车"，不是"对账"。
 *
 * <p>⚠️ <b>getter/setter 用 Lombok 生成，而且是标准命名（getXxx/setXxx）</b> ——
 * 这个类要被 Jackson 序列化进 Redis，而 Jackson 只认标准命名。
 * 曾经手写过 record 风格的 {@code maxTokens()} 读方法，结果序列化直接抛
 * {@code InvalidDefinitionException: No serializer found}：
 * 编译完全通过，运行到"暂停存状态"那一刻才炸。
 * ★ <b>判据：一个类只要会被序列化，它的访问器就必须符合生态约定，
 * 不能自创风格。</b>
 */
@Getter
@Setter
public final class Budget {

    private int maxTokens;
    private int perTaskTokens;
    private int usedTokens;

    public Budget() {
    }

    public Budget(int maxTokens, int perTaskTokens) {
        this(maxTokens, perTaskTokens, 0);
    }

    public Budget(int maxTokens, int perTaskTokens, int usedTokens) {
        this.maxTokens = Math.max(maxTokens, 1);
        this.perTaskTokens = Math.max(perTaskTokens, 1);
        this.usedTokens = Math.max(usedTokens, 0);
    }

    public void consume(int tokens) {
        usedTokens += Math.max(tokens, 0);
    }

    public int remaining() {
        return Math.max(maxTokens - usedTokens, 0);
    }

    public boolean exhausted() {
        return usedTokens >= maxTokens;
    }

    /**
     * 是否进入降级区（剩余不足 20%）。
     *
     * <p>编排器据此把下一批的并行度收小 —— <b>而不是等到耗尽才反应</b>。
     * 资源感知优化的要点是"边跑边调"，不是"跑完了发现超了"。
     */
    public boolean shouldDowngrade() {
        return remaining() * 5 <= maxTokens;
    }
}
