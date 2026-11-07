package com.queryon.web.controller;

import com.queryon.common.observability.CacheStats;
import com.queryon.common.observability.ObservationStore;
import com.queryon.web.security.IdentityResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 可观测性入口（D8-06）与结论溯源（D6-06）：全链路统计、按 traceId 查单次链路、
 * 查询结果缓存命中率（D3-08）。统计口径来自所有取数入口共用的受治理查询层，
 * 不含 prompt／completion 内容与凭据。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservationController {

    private final ObservationStore store;
    private final IdentityResolver identityResolver;
    private final ObjectProvider<CacheStats> cacheStatsProvider;

    public ObservationController(ObservationStore store, IdentityResolver identityResolver,
                                 ObjectProvider<CacheStats> cacheStatsProvider) {
        this.store = store;
        this.identityResolver = identityResolver;
        this.cacheStatsProvider = cacheStatsProvider;
    }

    @GetMapping("/metrics")
    public ObservationStore.Snapshot metrics(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return store.snapshot();
    }

    @GetMapping("/cache")
    public CacheStats.Snapshot cache(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return cacheStatsProvider.getIfAvailable(CacheStats::disabled).snapshot();
    }

    @GetMapping("/traces/{traceId}")
    public ObservationStore.Event trace(@PathVariable String traceId,
                                        @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return store.trace(traceId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该链路记录"));
    }
}
