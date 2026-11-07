package com.queryon.bootstrap.jdbc;

import com.queryon.semantic.plan.ExecutionPlan;
import com.queryon.semantic.plan.PlanFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把语义层 {@link ExecutionPlan} 渲染成 PostgreSQL 可执行 SQL。
 *
 * <p>本渲染器不解析自然语言、不重新选择指标口径，只把结构化计划翻译成
 * 带表别名限定、按需补齐维表 JOIN 的执行语句。权限过滤（如 sales_territory）
 * 与时间窗都来自 plan.filters，由系统注入，不来自调用方提示词。
 *
 * <p>{@link JdbcQueryExecutor#renderedSql(ExecutionPlan)} 与 {@link JdbcQueryExecutor#execute}
 * 共用本渲染器，因此接口返回的 {@code sql} 就是实际执行语句；护栏校验的仍是
 * {@code SemanticTranslator} 产出的计划快照，两者由同一份 plan 确定性映射，口径同源。
 */
public final class JdbcSqlRenderer {

    private static final Set<String> REGION =
            Set.of("sales_territory", "province", "city", "region_name");
    private static final Set<String> CUSTOMER =
            Set.of("customer_name", "customer_level", "phone", "email", "register_date");
    private static final Set<String> PRODUCT =
            Set.of("product_name", "category", "brand", "list_price", "cost");
    private static final Set<String> ITEM =
            Set.of("quantity", "unit_price", "item_amount");
    private static final Set<String> TABLES =
            Set.of("fact_order", "fact_order_item", "dim_region", "dim_customer", "dim_product");

    public String render(ExecutionPlan plan) {
        if (!TABLES.contains(plan.baseTable())) {
            throw new IllegalArgumentException("未授权的基表：" + plan.baseTable());
        }
        Set<String> referenced = new LinkedHashSet<>(plan.dimensionCodes());
        plan.filters().forEach(filter -> referenced.add(filter.column()));

        boolean needsRegion = referenced.stream().anyMatch(REGION::contains);
        boolean needsCustomer = referenced.stream().anyMatch(CUSTOMER::contains);
        boolean needsProduct = referenced.stream().anyMatch(PRODUCT::contains);
        boolean itemsBase = "fact_order_item".equals(plan.baseTable());
        // 只要执行路径落在订单明细表上（一对多），金额类指标就必须分摊，
        // 否则同一订单会在多行明细上重复计入净营收。
        boolean apportion = itemsBase || needsProduct;

        List<String> select = new ArrayList<>();
        for (String dimension : plan.dimensionCodes()) {
            select.add(qualify(dimension) + " AS " + dimension);
        }
        for (String metric : plan.metricCodes()) {
            select.add(metricExpression(metric, apportion) + " AS " + metric);
        }

        StringBuilder from = new StringBuilder("FROM ")
                .append(plan.baseTable()).append(itemsBase ? " i" : " o");
        if (itemsBase) {
            from.append("\nJOIN fact_order o ON i.order_id = o.order_id");
        }
        if (needsRegion) {
            from.append("\nJOIN dim_region r ON o.region_id = r.region_id");
        }
        if (needsCustomer) {
            from.append("\nJOIN dim_customer c ON o.customer_id = c.customer_id");
        }
        if (needsProduct) {
            if (!itemsBase) {
                from.append("\nJOIN fact_order_item i ON i.order_id = o.order_id");
            }
            from.append("\nJOIN dim_product p ON i.product_id = p.product_id");
        }
        if (apportion) {
            // 分摊分母取该订单全部明细金额之和，用派生表一次算好，
            // 避免相关子查询，也保证各分组之和等于订单口径总额。
            from.append("\nJOIN (SELECT order_id, SUM(item_amount) AS order_item_total "
                    + "FROM fact_order_item GROUP BY order_id) ot ON ot.order_id = o.order_id");
        }

        List<String> conditions = new ArrayList<>();
        for (PlanFilter filter : plan.filters()) {
            String column = qualify(filter.column());
            String value = "'" + escape(filter.values().getFirst()) + "'";
            switch (filter.operator()) {
                case EQ -> conditions.add(column + " = " + value);
                case NOT_EQ -> conditions.add(column + " <> " + value);
                case GTE -> conditions.add(column + " >= " + value);
                case LT -> conditions.add(column + " < " + value);
                case IN -> conditions.add(column + " IN (" + filter.values().stream()
                        .map(v -> "'" + escape(v) + "'")
                        .collect(Collectors.joining(", ")) + ")");
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", select)).append("\n").append(from);
        if (!conditions.isEmpty()) {
            sql.append("\nWHERE ").append(String.join(" AND ", conditions));
        }
        if (!plan.dimensionCodes().isEmpty()) {
            sql.append("\nGROUP BY ").append(plan.dimensionCodes().stream()
                    .map(this::qualify).collect(Collectors.joining(", ")));
        }
        if (plan.orderByMetric() != null && !plan.orderByMetric().isEmpty()) {
            sql.append("\nORDER BY ").append(plan.orderByMetric())
                    .append(plan.descending() ? " DESC" : " ASC");
        }
        sql.append("\nLIMIT ").append(plan.limit());
        return sql.toString();
    }

    /** 列所属表别名；与 02_create_tables.sql 的表结构一一对应。 */
    private String qualify(String column) {
        String alias;
        if (REGION.contains(column)) {
            alias = "r";
        } else if (CUSTOMER.contains(column)) {
            alias = "c";
        } else if (PRODUCT.contains(column)) {
            alias = "p";
        } else if (ITEM.contains(column)) {
            alias = "i";
        } else {
            alias = "o";
        }
        return alias + "." + column;
    }

    /** 与语义层认证指标定义同口径；金额列一律限定到订单主表别名 o。 */
    private String metricExpression(String code, boolean apportion) {
        String orderNet = "(o.pay_amount - o.refund_amount)";
        // 明细表上按明细金额占比分摊订单净营收；订单表上直接按订单累加。
        String net = apportion
                ? "SUM(CASE WHEN o.order_status IN ('paid','refunded') THEN " + orderNet
                  + " * i.item_amount / NULLIF(ot.order_item_total, 0) ELSE 0 END)"
                : "SUM(CASE WHEN o.order_status IN ('paid','refunded') THEN " + orderNet + " ELSE 0 END)";
        String orders = "COUNT(DISTINCT CASE WHEN o.order_status <> 'cancelled' THEN o.order_id END)";
        return switch (code) {
            case "net_revenue" -> net;
            case "order_count" -> orders;
            case "avg_order_value" -> net + " / NULLIF(" + orders + ", 0)";
            case "sales_volume" -> "SUM(i.quantity)";
            default -> throw new IllegalArgumentException("未受管指标：" + code);
        };
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
