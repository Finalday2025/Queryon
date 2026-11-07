package com.queryon.semantic;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.JoinPath;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import com.queryon.semantic.plan.ExecutionPlan;
import com.queryon.semantic.plan.PhysicalQuery;
import com.queryon.semantic.plan.PlanFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 唯一的语义查询 → 物理查询翻译器。join、公式与指标级过滤全部下放到语义层，
 * 所有入口共用它，因此同一指标在问数、SQL 编辑器、仪表板与开放 API 上口径一致。
 *
 * <p>{@link #translate(SemanticQuery)} 默认 PG 方言且与历史快照完全一致；
 * {@link #translate(SemanticQuery, SqlDialect)} 供按方言分组回归，仅改变标识符引用，
 * 执行计划与指标口径不变。
 */
public final class SemanticTranslator {

    private static final Set<String> PRODUCT_DIMENSIONS = Set.of(
            "category", "brand", "product_name");
    private static final Set<String> REGION_DIMENSIONS = Set.of(
            "sales_territory", "region_name", "province", "city");
    private static final Set<String> CUSTOMER_DIMENSIONS = Set.of(
            "customer_name", "customer_level", "phone", "email");

    private final SemanticRegistry registry;

    public SemanticTranslator(SemanticRegistry registry) {
        this.registry = registry;
    }

    public PhysicalQuery translate(SemanticQuery query) {
        return translate(query, SqlDialect.PG);
    }

    public PhysicalQuery translate(SemanticQuery query, SqlDialect dialect) {
        if (!query.hasMetrics()) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "语义查询未命中任何受管指标", "请指明要查询的认证指标，例如净营收、订单数、客单价或销量");
        }

        List<MetricDefinition> metricDefinitions = new ArrayList<>();
        for (String code : query.metrics()) {
            metricDefinitions.add(registry.requireMetric(code));
        }
        List<DimensionDefinition> dimensionDefinitions = new ArrayList<>();
        for (String code : query.dimensions()) {
            DimensionDefinition dimension = registry.dimension(code);
            if (dimension == null) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "Schema 门",
                        "维度 " + code + " 不在语义模型内", "请从维度目录中选择受管维度");
            }
            dimensionDefinitions.add(dimension);
        }

        boolean needsItems = query.metrics().contains(SemanticRegistry.METRIC_SALES_VOLUME)
                || query.dimensions().stream().anyMatch(PRODUCT_DIMENSIONS::contains);
        String baseTable = needsItems ? SemanticRegistry.FACT_ORDER_ITEM : SemanticRegistry.FACT_ORDER;

        LinkedHashSet<String> requiredTables = new LinkedHashSet<>();
        requiredTables.add(baseTable);
        List<JoinPath> joinPaths = new ArrayList<>();
        if (needsItems) {
            requiredTables.add(SemanticRegistry.FACT_ORDER);
            requiredTables.add(SemanticRegistry.DIM_PRODUCT);
            joinPaths.add(findJoin(SemanticRegistry.FACT_ORDER_ITEM, SemanticRegistry.FACT_ORDER));
            joinPaths.add(findJoin(SemanticRegistry.FACT_ORDER_ITEM, SemanticRegistry.DIM_PRODUCT));
        }
        if (query.dimensions().stream().anyMatch(REGION_DIMENSIONS::contains)) {
            requiredTables.add(SemanticRegistry.DIM_REGION);
            joinPaths.add(findJoin(SemanticRegistry.FACT_ORDER, SemanticRegistry.DIM_REGION));
        }
        if (query.dimensions().stream().anyMatch(CUSTOMER_DIMENSIONS::contains)) {
            requiredTables.add(SemanticRegistry.DIM_CUSTOMER);
            joinPaths.add(findJoin(SemanticRegistry.FACT_ORDER, SemanticRegistry.DIM_CUSTOMER));
        }

        List<PlanFilter> filters = new ArrayList<>();
        TimeWindow window = query.timeWindow();
        if (window.type() != TimeWindow.Type.ALL) {
            filters.add(PlanFilter.gte(SemanticRegistry.COL_ORDER_DATE, window.start().toString()));
            filters.add(PlanFilter.lt(SemanticRegistry.COL_ORDER_DATE, window.end().toString()));
        }
        for (Map.Entry<String, String> entry : query.filters().entrySet()) {
            filters.add(PlanFilter.eq(entry.getKey(), entry.getValue()));
        }

        String sql = renderSql(baseTable, joinPaths, metricDefinitions, dimensionDefinitions,
                filters, query.limit(), query.orderByMetric(), query.descending(), dialect);
        ExecutionPlan plan = new ExecutionPlan(baseTable, new ArrayList<>(requiredTables),
                query.metrics(), query.dimensions(), filters, joinPaths,
                query.limit(), query.orderByMetric(), query.descending());
        return new PhysicalQuery(sql, plan);
    }

    private JoinPath findJoin(String fromTable, String toTable) {
        return registry.approvedJoins().stream()
                .filter(path -> path.fromTable().equals(fromTable) && path.toTable().equals(toTable))
                .findFirst()
                .orElseThrow(() -> new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "Schema 门",
                        "未批准的关联路径 " + fromTable + " -> " + toTable,
                        "只允许语义层批准的星型连接"));
    }

    private String renderSql(String baseTable, List<JoinPath> joinPaths,
                             List<MetricDefinition> metrics, List<DimensionDefinition> dimensions,
                             List<PlanFilter> filters, int limit, String orderByMetric,
                             boolean descending, SqlDialect dialect) {
        List<String> selectItems = new ArrayList<>();
        for (DimensionDefinition dimension : dimensions) {
            selectItems.add(dialect.quote(dimension.column()) + " AS " + dialect.quote(dimension.code()));
        }
        for (MetricDefinition metric : metrics) {
            selectItems.add(metricExpression(metric.code()) + " AS " + dialect.quote(metric.code()));
        }

        Map<String, String> alias = Map.of(
                SemanticRegistry.FACT_ORDER, "o",
                SemanticRegistry.FACT_ORDER_ITEM, "i",
                SemanticRegistry.DIM_REGION, "r",
                SemanticRegistry.DIM_PRODUCT, "p",
                SemanticRegistry.DIM_CUSTOMER, "c");
        StringBuilder from = new StringBuilder("FROM ").append(dialect.quote(baseTable))
                .append(" ").append(alias.get(baseTable));
        for (JoinPath path : joinPaths) {
            String fromAlias = alias.getOrDefault(path.fromTable(), alias.get(baseTable));
            String toAlias = alias.get(path.toTable());
            from.append("\nJOIN ").append(dialect.quote(path.toTable())).append(" ").append(toAlias)
                    .append(" ON ").append(fromAlias).append(".").append(dialect.quote(path.fromKey()))
                    .append(" = ").append(toAlias).append(".").append(dialect.quote(path.toKey()));
        }

        List<String> conditions = new ArrayList<>();
        for (PlanFilter filter : filters) {
            String column = dialect.quote(filter.column());
            switch (filter.operator()) {
                case EQ -> conditions.add(column + " = '" + escape(filter.values().getFirst()) + "'");
                case GTE -> conditions.add(column + " >= '" + escape(filter.values().getFirst()) + "'");
                case LT -> conditions.add(column + " < '" + escape(filter.values().getFirst()) + "'");
                case IN -> conditions.add(column + " IN (" + filter.values().stream()
                        .map(value -> "'" + escape(value) + "'").reduce((a, b) -> a + ", " + b).orElse("") + ")");
                case NOT_EQ -> conditions.add(column + " <> '" + escape(filter.values().getFirst()) + "'");
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectItems)).append("\n")
                .append(from);
        if (!conditions.isEmpty()) {
            sql.append("\nWHERE ").append(String.join(" AND ", conditions));
        }
        if (!dimensions.isEmpty()) {
            sql.append("\nGROUP BY ").append(dimensions.stream()
                    .map(dimension -> dialect.quote(dimension.column()))
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        String orderBy = orderByMetric == null ? "" : orderByMetric;
        if (!orderBy.isEmpty()) {
            sql.append("\nORDER BY ").append(dialect.quote(orderBy))
                    .append(descending ? " DESC" : " ASC");
        }
        sql.append("\nLIMIT ").append(limit);
        return sql.toString();
    }

    /** 口径表达，与执行器按指标定义计算的结果一致，仅供 AST 校验与溯源展示。 */
    private String metricExpression(String code) {
        return switch (code) {
            case SemanticRegistry.METRIC_NET_REVENUE ->
                    "SUM(CASE WHEN order_status IN ('paid','refunded') THEN pay_amount - refund_amount ELSE 0 END)";
            case SemanticRegistry.METRIC_ORDER_COUNT ->
                    "COUNT(DISTINCT CASE WHEN order_status <> 'cancelled' THEN order_id END)";
            case SemanticRegistry.METRIC_AVG_ORDER_VALUE ->
                    "SUM(CASE WHEN order_status IN ('paid','refunded') THEN pay_amount - refund_amount ELSE 0 END)"
                            + " / COUNT(DISTINCT CASE WHEN order_status <> 'cancelled' THEN order_id END)";
            case SemanticRegistry.METRIC_SALES_VOLUME -> "SUM(quantity)";
            default -> throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "指标 " + code + " 无可用口径表达", "请从指标目录中选择认证口径");
        };
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
