package com.queryon.query.executor;

import com.queryon.common.result.ResultSet;
import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.plan.ExecutionPlan;
import com.queryon.semantic.plan.PlanFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 基于内置假数据集宽表视图的执行器；未来可替换为同库的 PostgreSQL 执行实现。 */
public final class InMemoryQueryExecutor implements QueryExecutor {

    private final SampleDataset dataset;

    public InMemoryQueryExecutor(SampleDataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public long estimateRowCount(String table) {
        return dataset.rowCount(table);
    }

    @Override
    public ResultSet execute(ExecutionPlan plan) {
        List<Map<String, Object>> filtered = dataset.rows(plan.baseTable()).stream()
                .filter(row -> matchesAll(row, plan.filters()))
                .toList();

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : filtered) {
            groups.computeIfAbsent(groupKey(row, plan.dimensionCodes()), key -> new ArrayList<>()).add(row);
        }

        // 明细表上一对多会放大订单金额；按全量订单明细金额作为分摊分母，
        // 与 JdbcSqlRenderer 的 SQL 分摊表达式保持同一口径。
        Map<Object, Double> orderItemTotals = SemanticRegistry.FACT_ORDER_ITEM.equals(plan.baseTable())
                ? buildOrderItemTotals() : null;

        List<String> columns = new ArrayList<>(plan.dimensionCodes());
        columns.addAll(plan.metricCodes());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            Map<String, Object> output = new LinkedHashMap<>();
            List<Map<String, Object>> groupRows = group.getValue();
            Map<String, Object> first = groupRows.getFirst();
            for (String dimension : plan.dimensionCodes()) {
                output.put(dimension, first.get(dimension));
            }
            for (String metric : plan.metricCodes()) {
                output.put(metric, MetricEvaluator.evaluate(metric, groupRows, orderItemTotals));
            }
            rows.add(output);
        }

        sort(rows, plan);
        int totalRows = rows.size();
        boolean truncated = totalRows > plan.limit();
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, plan.limit()));
        }
        return new ResultSet(columns, rows, totalRows, truncated);
    }

    /** 全量订单明细金额之和（分摊分母）；不随当前分组或过滤条件变化。 */
    private Map<Object, Double> buildOrderItemTotals() {
        Map<Object, Double> totals = new HashMap<>();
        for (Map<String, Object> row : dataset.rows(SemanticRegistry.FACT_ORDER_ITEM)) {
            Object orderId = row.get("order_id");
            if (orderId == null) {
                continue;
            }
            Object amount = row.get("item_amount");
            double value = amount instanceof Number number ? number.doubleValue() : 0.0;
            totals.merge(orderId, value, Double::sum);
        }
        return totals;
    }

    private static boolean matchesAll(Map<String, Object> row, List<PlanFilter> filters) {
        for (PlanFilter filter : filters) {
            if (!matches(row, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Map<String, Object> row, PlanFilter filter) {
        Object raw = row.get(filter.column());
        if (raw == null) {
            return false;
        }
        String actual = String.valueOf(raw);
        String expected = filter.values().isEmpty() ? "" : filter.values().getFirst();
        return switch (filter.operator()) {
            case EQ -> actual.equals(expected);
            case NOT_EQ -> !actual.equals(expected);
            case GTE -> actual.compareTo(expected) >= 0;
            case LT -> actual.compareTo(expected) < 0;
            case IN -> filter.values().contains(actual);
        };
    }

    private static String groupKey(Map<String, Object> row, List<String> dimensions) {
        if (dimensions.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String dimension : dimensions) {
            parts.add(String.valueOf(row.get(dimension)));
        }
        return String.join("|", parts);
    }

    private static void sort(List<Map<String, Object>> rows, ExecutionPlan plan) {
        String orderBy = plan.orderByMetric();
        if (orderBy == null || rows.isEmpty()) {
            return;
        }
        Comparator<Map<String, Object>> comparator = Comparator.comparing(
                (Map<String, Object> row) -> {
                    Object value = row.get(orderBy);
                    return value instanceof Number number ? number.doubleValue() : Double.NaN;
                },
                Comparator.nullsLast(Comparator.naturalOrder()));
        if (plan.descending()) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);
    }
}
