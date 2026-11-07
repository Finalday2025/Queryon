package com.queryon.bootstrap.metrics;

import com.queryon.common.observability.QueryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer 实现（D8-06）：把受治理查询的耗时、行数、成功／拦截与错误码导出为可采集指标。
 *
 * <p>只导出可聚合的数值与有界的结构化错误码标签，不记录 prompt／completion、SQL 文本或身份，
 * 避免标签基数失控与敏感内容外泄。
 */
public final class MicrometerQueryMetrics implements QueryMetrics {

    private final MeterRegistry registry;
    private final long slowThresholdMs;
    private final Counter success;
    private final Counter rejected;
    private final Counter slow;
    private final Timer latency;
    private final DistributionSummary rows;

    public MicrometerQueryMetrics(MeterRegistry registry, long slowThresholdMs) {
        this.registry = registry;
        this.slowThresholdMs = slowThresholdMs;
        this.success = Counter.builder("queryon.query.calls")
                .description("受治理查询调用次数")
                .tag("outcome", "success")
                .register(registry);
        this.rejected = Counter.builder("queryon.query.calls")
                .description("受治理查询调用次数")
                .tag("outcome", "rejected")
                .register(registry);
        this.slow = Counter.builder("queryon.query.slow")
                .description("超过慢查询阈值的调用次数")
                .register(registry);
        this.latency = Timer.builder("queryon.query.latency")
                .description("受治理查询耗时（含护栏校验与执行）")
                .register(registry);
        this.rows = DistributionSummary.builder("queryon.query.rows")
                .description("单次查询返回行数")
                .register(registry);
    }

    @Override
    public void record(long durationMs, boolean successFlag, String errorCode, int rowCount) {
        latency.record(Duration.ofMillis(Math.max(durationMs, 0)));
        rows.record(rowCount);
        (successFlag ? success : rejected).increment();
        if (durationMs >= slowThresholdMs) {
            slow.increment();
        }
        if (!successFlag && errorCode != null && !errorCode.isBlank()) {
            Counter.builder("queryon.query.intercepts")
                    .description("护栏拦截次数，按结构化错误码区分")
                    .tag("code", errorCode)
                    .register(registry)
                    .increment();
        }
    }
}
