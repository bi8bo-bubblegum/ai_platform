package com.best.ai_orchestrator.core;

import com.best.ai_common.mcp.ToolRisk;

/**
 * 一个挂起的审批请求。
 *
 * <p><b>它是"暂停"的全部内容</b>：编排器把这个对象交给上层，上层拿它去问人；
 * 人答完之后，把 {@code approved} 传回来继续。
 *
 * <p>刻意带上 {@code taskGoal} —— 光看工具名和参数（比如
 * {@code mysql__mysql_query {"sql":"DELETE FROM ..."}}）人很难判断"该不该批"；
 * 加上"这是在为哪个目标服务"，审批才有信息基础。
 */
public record PendingApproval(String taskId,
                              String taskGoal,
                              String tool,
                              String args,
                              ToolRisk risk,
                              String reason) {
}
