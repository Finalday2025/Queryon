package com.queryon.common.audit;

/**
 * 审计记录覆盖一次问数全链路，含护栏拦截。只写脱敏后的结构化事件。
 *
 * @param traceId 链路标识
 * @param userId  提问者
 * @param role    身份角色
 * @param action  动作
 * @param outcome 结果（成功／拦截／澄清／拒绝）
 * @param detail  结构化原因
 */
public record AuditEvent(String traceId, String userId, String role, String action,
                         String outcome, String detail) {
}
