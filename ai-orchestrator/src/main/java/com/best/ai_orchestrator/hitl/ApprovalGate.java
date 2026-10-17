package com.best.ai_orchestrator.hitl;

import com.best.ai_common.mcp.ToolRisk;

/**
 * 审批网关 —— <b>决定"哪些工具调用必须先问过人"</b>。
 *
 * <p><b>它是"人在回路"的落点，也是最容易被做错的地方。</b>
 * 做错的方式只有一种：<b>先执行、再问要不要批准</b>。
 * 那样审批就成了摆设 —— 因为工具已经跑了。
 *
 * <p>本模块的做法是把判断放在<b>提议之后、执行之前</b>，见
 * {@code WorkerExecutor} 的循环：一旦判定要审批，
 * <b>立刻返回、不执行，并把整个 RunState 交出去</b>。
 *
 * <p>做成接口（而不是写死阈值）是为了三件事都留出余地：
 * 按阈值批、按参数批（"删 3 天数据"和"删全部数据"是同一个工具但风险不同）、
 * 或者干脆接一个外部审批系统。
 */
public interface ApprovalGate {

    /**
     * @param risk 这个工具的静态危险等级（来自 {@code mcp_gateway.tools.risk}）
     * @return true 表示这次调用必须停下等人
     */
    boolean requiresApproval(ToolRisk risk);

    /** 默认实现：达到或超过阈值就要批准 */
    static ApprovalGate threshold(ToolRisk minRisk) {
        return risk -> risk != null && risk.atLeast(minRisk);
    }

    /** 全部放行 —— <b>只该在本地调试用</b> */
    static ApprovalGate open() {
        return risk -> false;
    }
}
