package com.queryon.semantic.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义查询中间表示：指标 + 维度 + 时间窗 + 等值过滤。
 * 自然语言、SQL 编辑器、仪表板、开放 API 共用这一层，保证同一指标口径一致。
 */
public record SemanticQuery(List<String> metrics, List<String> dimensions,
                            TimeWindow timeWindow, Map<String, String> filters,
                            int limit, String orderByMetric, boolean descending) {

    public static final int DEFAULT_LIMIT = 100;

    public SemanticQuery {
        metrics = List.copyOf(metrics);
        dimensions = List.copyOf(dimensions);
        filters = Map.copyOf(filters);
        if (timeWindow == null) {
            timeWindow = TimeWindow.all();
        }
    }

    public static SemanticQuery empty() {
        return new SemanticQuery(List.of(), List.of(), TimeWindow.all(), Map.of(),
                DEFAULT_LIMIT, null, false);
    }

    public SemanticQuery withMetrics(List<String> newMetrics) {
        return new SemanticQuery(newMetrics, dimensions, timeWindow, filters, limit, orderByMetric, descending);
    }

    public SemanticQuery withDimensions(List<String> newDimensions) {
        return new SemanticQuery(metrics, newDimensions, timeWindow, filters, limit, orderByMetric, descending);
    }

    public SemanticQuery withTimeWindow(TimeWindow newTimeWindow) {
        return new SemanticQuery(metrics, dimensions, newTimeWindow, filters, limit, orderByMetric, descending);
    }

    public SemanticQuery withFilter(String column, String value) {
        Map<String, String> newFilters = new LinkedHashMap<>(filters);
        newFilters.put(column, value);
        return new SemanticQuery(metrics, dimensions, timeWindow, newFilters, limit, orderByMetric, descending);
    }

    public SemanticQuery withLimit(int newLimit) {
        return new SemanticQuery(metrics, dimensions, timeWindow, filters, newLimit, orderByMetric, descending);
    }

    public SemanticQuery withOrder(String metric, boolean desc) {
        return new SemanticQuery(metrics, dimensions, timeWindow, filters, limit, metric, desc);
    }

    public boolean hasMetrics() {
        return !metrics.isEmpty();
    }

    /** 评测断言用的语义指纹，只含可校验的语义约束，不含数值结果。 */
    public String fingerprint() {
        return "metrics=" + metrics + ", dimensions=" + dimensions
                + ", time=" + timeWindow.type() + ", filters=" + filters
                + ", limit=" + limit + ", order=" + orderByMetric;
    }
}
