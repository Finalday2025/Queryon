package com.queryon.common.audit;

import java.util.List;

/** 审计日志端口，便于替换为持久化实现。 */
public interface AuditLog {

    void record(AuditEvent event);

    List<AuditEvent> events();
}
