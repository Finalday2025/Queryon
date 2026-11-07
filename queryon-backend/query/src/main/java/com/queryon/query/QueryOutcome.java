package com.queryon.query;

import com.queryon.common.context.AskTrace;
import com.queryon.common.result.ResultSet;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.plan.ExecutionPlan;

import java.util.List;

/**
 * 一次受治理查询的完整输出：结果表 + 可解释信息（指标口径、查询快照、链路记录）。
 * 结论可沿「指标层 → 语义模型层 → 物理数据层」回溯（G5）。
 */
public record QueryOutcome(ResultSet resultSet, String sql, ExecutionPlan plan,
                           List<MetricDefinition> metricDefinitions, AskTrace trace) {

    public QueryOutcome {
        metricDefinitions = List.copyOf(metricDefinitions);
    }
}
