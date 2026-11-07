package com.queryon.web.service;

import com.queryon.common.context.Actor;
import com.queryon.common.result.ResultSet;
import com.queryon.query.QueryOutcome;
import com.queryon.query.QueryService;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;

import java.time.LocalDate;
import java.util.List;

/**
 * 仪表板（D3-01～D3-04、D3-06）：每个卡片都通过受治理查询唯一入口生成，
 * 因此与问数、SQL 编辑器、开放 API 的同一指标结果一致（02 第 6.3 节判据 2）。
 */
public final class DashboardService {

    public record Card(String id, String title, String chartType, ResultSet resultSet,
                       List<String> metricDefinitions, String sql, String traceId) {
    }

    public record Dashboard(List<Card> cards, String timeWindow) {
    }

    private final QueryService queryService;

    public DashboardService(QueryService queryService) {
        this.queryService = queryService;
    }

    public Dashboard build(String timeWindow, Actor actor) {
        String normalized = timeWindow == null ? "LAST_MONTH" : timeWindow.toUpperCase();
        TimeWindow window = switch (normalized) {
            case "YEAR_TO_DATE" -> TimeWindow.yearToDate(LocalDate.now());
            case "ALL" -> TimeWindow.all();
            default -> TimeWindow.lastMonth(LocalDate.now());
        };
        String label = switch (normalized) {
            case "YEAR_TO_DATE" -> "YEAR_TO_DATE";
            case "ALL" -> "ALL";
            default -> "LAST_MONTH";
        };
        List<Card> cards = List.of(
                card("total", "净营收", "metric", List.of("net_revenue"), List.of(), window, actor),
                card("by-region", "各销售大区净营收", "bar",
                        List.of("net_revenue"), List.of("sales_territory"), window, actor),
                card("by-category", "各品类订单数", "bar",
                        List.of("order_count"), List.of("category"), window, actor),
                card("aov", "客单价", "metric", List.of("avg_order_value"), List.of(), window, actor));
        return new Dashboard(cards, label);
    }

    private Card card(String id, String title, String chartType,
                      List<String> metrics, List<String> dimensions,
                      TimeWindow window, Actor actor) {
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(metrics)
                .withDimensions(dimensions)
                .withTimeWindow(window)
                .withLimit(50);
        QueryOutcome outcome = queryService.execute(query, actor);
        List<String> definitions = outcome.metricDefinitions().stream()
                .map(metric -> metric.businessName() + "：" + metric.definition()
                        + "｜单位 " + metric.unit() + "｜版本 " + metric.version())
                .toList();
        return new Card(id, title, chartType, outcome.resultSet(), definitions,
                outcome.sql(), outcome.trace().traceId());
    }
}
