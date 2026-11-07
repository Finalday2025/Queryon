package com.queryon.web.subscription;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.context.Actor;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dataset.SubscriptionStore;
import com.queryon.web.dto.QueryResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 进程内定时订阅（D3-07）：使用 JDK 内置调度，不引入 Quartz／XXL-JOB 等第三方依赖。
 * 周期任务与手动执行共用同一执行方法，均走受治理查询入口并留审计；
 * 调度状态为内存结构，进程重启后需重新注册。
 */
@Component
public class SubscriptionScheduler {

    public record ScheduleInfo(String id, String name, long intervalSeconds, long runCount) {
    }

    private final SubscriptionStore store;
    private final QueryService queryService;
    private final SqlSemanticReader sqlSemanticReader;
    private final AuditLog auditLog;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, Long> intervals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "queryon-subscription");
        thread.setDaemon(true);
        return thread;
    });

    public SubscriptionScheduler(SubscriptionStore store, QueryService queryService,
                                 SqlSemanticReader sqlSemanticReader, AuditLog auditLog) {
        this.store = store;
        this.queryService = queryService;
        this.sqlSemanticReader = sqlSemanticReader;
        this.auditLog = auditLog;
    }

    /** 手动执行与定时执行共用，保证口径与留痕一致。 */
    public QueryResponse runOnce(String id, Actor actor) {
        SubscriptionStore.Subscription subscription = store.get(id, actor.userId());
        var request = subscription.query();
        SemanticQuery query = request.sql() != null && !request.sql().isBlank()
                ? sqlSemanticReader.read(request.sql())
                : request.toSemanticQuery(LocalDate.now());
        QueryResponse response = QueryResponse.from(queryService.execute(query, actor));
        store.markRun(id, actor.userId(), "成功 · 行数=" + response.resultSet().rows().size());
        auditLog.record(new AuditEvent(response.traceId(), actor.userId(), actor.role(),
                "订阅执行", "成功", subscription.name() + "／" + subscription.id()));
        return response;
    }

    public ScheduleInfo schedule(String id, Actor actor, long intervalSeconds) {
        if (intervalSeconds < 5) {
            throw new IllegalArgumentException("调度周期不得小于 5 秒");
        }
        SubscriptionStore.Subscription subscription = store.get(id, actor.userId());
        ScheduledFuture<?> previous = futures.remove(id);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                runOnce(id, actor);
            } catch (RuntimeException ignored) {
                store.markRun(id, actor.userId(), "执行失败");
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        futures.put(id, future);
        intervals.put(id, intervalSeconds);
        return new ScheduleInfo(id, subscription.name(), intervalSeconds, subscription.runCount());
    }

    public ScheduleInfo cancel(String id, Actor actor) {
        SubscriptionStore.Subscription subscription = store.get(id, actor.userId());
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
        intervals.remove(id);
        return new ScheduleInfo(id, subscription.name(), 0, subscription.runCount());
    }

    public Map<String, Object> active(Actor actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        futures.forEach((id, future) -> {
            if (future.isCancelled()) {
                return;
            }
            try {
                SubscriptionStore.Subscription subscription = store.get(id, actor.userId());
                result.put(id, new ScheduleInfo(id, subscription.name(),
                        intervals.getOrDefault(id, 0L), subscription.runCount()));
            } catch (RuntimeException ignored) {
                // 非当前身份的调度不暴露
            }
        });
        return result;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
