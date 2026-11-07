package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.web.security.IdentityResolver;
import com.queryon.web.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仪表板入口：全局时间窗下的一组受治理卡片，权限随提问者身份注入。 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final IdentityResolver identityResolver;

    public DashboardController(DashboardService dashboardService, IdentityResolver identityResolver) {
        this.dashboardService = dashboardService;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public DashboardService.Dashboard dashboard(
            @RequestParam(required = false) String timeWindow,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return dashboardService.build(timeWindow, actor);
    }
}
