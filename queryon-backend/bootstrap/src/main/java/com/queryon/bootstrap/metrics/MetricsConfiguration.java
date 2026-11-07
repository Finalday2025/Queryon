package com.queryon.bootstrap.metrics;

import com.queryon.common.observability.ObservationStore;
import com.queryon.common.observability.QueryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测导出装配（D8-06）：把 Micrometer 指标接到受治理查询的唯一编排入口。
 *
 * <p>MeterRegistry 由 Spring Boot Actuator 与 micrometer-registry-prometheus 提供；
 * 若运行环境未装配 registry（例如裁剪过的嵌入式场景），query 层自动回退
 * {@link QueryMetrics#NOOP}，治理链路不受影响。
 */
@Configuration
public class MetricsConfiguration {

    @Bean
    public QueryMetrics queryMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return QueryMetrics.NOOP;
        }
        return new MicrometerQueryMetrics(registry, ObservationStore.SLOW_QUERY_MS);
    }
}
