package com.queryon.web.dto;

import com.queryon.common.context.AskTrace;
import com.queryon.common.result.ResultSet;
import com.queryon.query.QueryOutcome;

import java.util.List;

/** 查询响应：结果表 + 口径 + 查询快照 + 链路记录，避免直接序列化内部对象。 */
public record QueryResponse(ResultSet resultSet, String sql, List<String> metricDefinitions,
                            String traceId, List<AskTrace.Step> steps) {

    public static QueryResponse from(QueryOutcome outcome) {
        return new QueryResponse(outcome.resultSet(), outcome.sql(),
                outcome.metricDefinitions().stream()
                        .map(metric -> metric.businessName() + "：" + metric.definition()
                                + "｜公式 " + metric.expression()
                                + "｜过滤 " + metric.defaultFilter()
                                + "｜版本 " + metric.version())
                        .toList(),
                outcome.trace().traceId(), outcome.trace().steps());
    }
}
