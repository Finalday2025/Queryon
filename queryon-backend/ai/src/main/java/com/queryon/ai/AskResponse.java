package com.queryon.ai;

import com.queryon.common.context.AskTrace;
import com.queryon.common.error.StructuredError;
import com.queryon.common.result.ResultSet;
import com.queryon.semantic.model.SemanticQuery;

import java.util.List;

/**
 * 问数响应：结果表 + 图表数据 + 指标口径 + 查询快照 + 全链路记录，
 * 澄清与拦截也走同一结构，便于前端与评测统一处理。
 * interpretation／suggestions 为基础数据解读与后续推荐（D4-06／D4-09、D5 社区版部分）。
 */
public record AskResponse(Status status, String question, SemanticQuery semanticQuery,
                          ResultSet resultSet, String chartType, String sqlSnapshot,
                          List<String> metricDefinitions, String clarification,
                          StructuredError error, String traceId, List<AskTrace.Step> steps,
                          List<String> interpretation, List<String> suggestions) {

    public enum Status {
        ANSWERED, DEFINITION, CLARIFICATION, REJECTED
    }

    public AskResponse {
        metricDefinitions = List.copyOf(metricDefinitions);
        steps = List.copyOf(steps);
        interpretation = List.copyOf(interpretation);
        suggestions = List.copyOf(suggestions);
    }

    public static AskResponse clarification(String question, String clarification, String traceId,
                                            List<AskTrace.Step> steps) {
        return new AskResponse(Status.CLARIFICATION, question, SemanticQuery.empty(),
                ResultSet.empty(), null, null, List.of(), clarification, null, traceId, steps,
                List.of(), List.of());
    }

    public static AskResponse rejected(String question, StructuredError error, String traceId,
                                       List<AskTrace.Step> steps) {
        return new AskResponse(Status.REJECTED, question, SemanticQuery.empty(),
                ResultSet.empty(), null, null, List.of(), null, error, traceId, steps,
                List.of(), List.of());
    }

    public static AskResponse definition(String question, List<String> notes, String traceId,
                                         List<AskTrace.Step> steps) {
        return new AskResponse(Status.DEFINITION, question, SemanticQuery.empty(),
                ResultSet.empty(), "definition", null, notes, null, null, traceId, steps,
                List.of(), List.of());
    }
}
