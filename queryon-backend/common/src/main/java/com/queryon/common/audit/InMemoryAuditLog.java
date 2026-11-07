package com.queryon.common.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存审计日志；骨架阶段不引入持久化组件。 */
public final class InMemoryAuditLog implements AuditLog {

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> events() {
        return List.copyOf(events);
    }
}
