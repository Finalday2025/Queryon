package com.queryon.web.dto;

import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统一查询请求：SQL 编辑器可提交 sql，仪表板与开放 API 可提交结构化语义查询，
 * 两条路径最终都归一到同一个 SemanticQuery。
 */
public record QueryRequest(List<String> metrics, List<String> dimensions, String timeWindow,
                           Map<String, String> filters, Integer limit, String sql) {

    public SemanticQuery toSemanticQuery(LocalDate today) {
        List<String> safeMetrics = metrics == null ? List.of() : metrics;
        List<String> safeDimensions = dimensions == null ? List.of() : dimensions;
        Map<String, String> safeFilters = filters == null ? Map.of() : filters;
        TimeWindow window = switch (timeWindow == null ? "ALL" : timeWindow.toUpperCase()) {
            case "LAST_MONTH" -> TimeWindow.lastMonth(today);
            case "YEAR_TO_DATE" -> TimeWindow.yearToDate(today);
            default -> TimeWindow.all();
        };
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(safeMetrics)
                .withDimensions(safeDimensions)
                .withTimeWindow(window)
                .withLimit(limit == null ? SemanticQuery.DEFAULT_LIMIT : limit);
        for (Map.Entry<String, String> filter : safeFilters.entrySet()) {
            query = query.withFilter(filter.getKey(), filter.getValue());
        }
        return query;
    }
}
