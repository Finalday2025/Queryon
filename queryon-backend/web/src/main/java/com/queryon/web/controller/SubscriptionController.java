package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.web.dataset.SubscriptionStore;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import com.queryon.web.subscription.SubscriptionScheduler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 订阅台账（D3-07）：保存、列出、立即执行，以及进程内周期调度与取消。 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    public record SubscriptionRequest(String name, String frequency, QueryRequest query) {
    }

    private final SubscriptionStore store;
    private final SubscriptionScheduler scheduler;
    private final IdentityResolver identityResolver;

    public SubscriptionController(SubscriptionStore store, SubscriptionScheduler scheduler,
                                  IdentityResolver identityResolver) {
        this.store = store;
        this.scheduler = scheduler;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public SubscriptionStore.Subscription save(
            @RequestBody SubscriptionRequest request,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        if (request == null || request.query() == null) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "订阅",
                    "缺少查询定义", "请提供 metrics／dimensions 或 sql");
        }
        return store.save(request.name(), request.frequency(), request.query(), actor.userId());
    }

    @GetMapping
    public List<SubscriptionStore.Subscription> list(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.list(actor.userId());
    }

    @PostMapping("/{id}/run")
    public QueryResponse run(@PathVariable String id,
                             @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return scheduler.runOnce(id, actor);
    }

    @PostMapping("/{id}/schedule")
    public SubscriptionScheduler.ScheduleInfo schedule(
            @PathVariable String id,
            @RequestParam(defaultValue = "60") long intervalSeconds,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return scheduler.schedule(id, actor, intervalSeconds);
    }

    @DeleteMapping("/{id}/schedule")
    public SubscriptionScheduler.ScheduleInfo cancelSchedule(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return scheduler.cancel(id, actor);
    }

    @GetMapping("/schedules")
    public Map<String, Object> activeSchedules(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return scheduler.active(actor);
    }
}
