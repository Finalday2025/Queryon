package com.queryon.web.controller;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 基础审计台账（D7-05）：护栏拦截与成功取数可检索，事件已脱敏、不含问句与凭据。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLog auditLog;
    private final IdentityResolver identityResolver;

    public AuditController(AuditLog auditLog, IdentityResolver identityResolver) {
        this.auditLog = auditLog;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/events")
    public List<AuditEvent> events(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Queryon-User", required = false) String actorHeader) {
        identityResolver.resolve(actorHeader);
        List<AuditEvent> events = new ArrayList<>(auditLog.events());
        java.util.Collections.reverse(events);
        return events.stream()
                .filter(event -> outcome == null || outcome.isBlank() || outcome.equals(event.outcome()))
                .filter(event -> userId == null || userId.isBlank() || userId.equals(event.userId()))
                .limit(Math.max(1, Math.min(limit, 500)))
                .toList();
    }
}
