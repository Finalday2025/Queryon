package com.queryon.semantic.plan;

import com.queryon.semantic.model.JoinPath;

import java.util.List;

/**
 * 由语义层翻译出的执行计划。执行器只消费这份结构化计划，
 * 不解析 SQL 字符串，也不会绕过语义层直接面对物理表。
 */
public record ExecutionPlan(String baseTable, List<String> requiredTables,
                            List<String> metricCodes, List<String> dimensionCodes,
                            List<PlanFilter> filters, List<JoinPath> joinPaths,
                            int limit, String orderByMetric, boolean descending) {

    public ExecutionPlan {
        requiredTables = List.copyOf(requiredTables);
        metricCodes = List.copyOf(metricCodes);
        dimensionCodes = List.copyOf(dimensionCodes);
        filters = List.copyOf(filters);
        joinPaths = List.copyOf(joinPaths);
    }
}
