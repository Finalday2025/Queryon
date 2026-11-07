package com.queryon.query.executor;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.SemanticRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证口径的唯一计算实现；公式与过滤来自 05 第 8.3 节。
 *
 * <p>当查询落在订单明细表（按品类／商品维度拆解或含销量指标）时，金额类指标按
 * 「明细行金额占该订单全部明细金额的比例」把订单净营收分摊到各明细行，
 * 使各分组之和等于订单口径总额；订单数、销量仍按各自认证口径计算。
 */
public final class MetricEvaluator {

    private MetricEvaluator() {
    }

    public static double evaluate(String metricCode, List<Map<String, Object>> groupRows) {
        return evaluate(metricCode, groupRows, null);
    }

    /**
     * @param orderItemTotals 订单标识 → 该订单<strong>全部</strong>明细金额之和；
     *                        非 null 表示当前计划落在明细表上，需启用分摊。
     *                        分母用全量而非分组内小计，保证各分组之和等于订单总额。
     */
    public static double evaluate(String metricCode, List<Map<String, Object>> groupRows,
                                  Map<Object, Double> orderItemTotals) {
        boolean apportion = orderItemTotals != null;
        List<Map<String, Object>> orders = distinctOrders(groupRows);
        double net = apportion
                ? apportionedNet(groupRows, orderItemTotals)
                : netRevenue(orders);
        return switch (metricCode) {
            case SemanticRegistry.METRIC_NET_REVENUE -> net;
            case SemanticRegistry.METRIC_ORDER_COUNT -> orderCount(orders);
            case SemanticRegistry.METRIC_AVG_ORDER_VALUE -> {
                double count = orderCount(orders);
                yield count == 0.0 ? 0.0 : round(net / count);
            }
            case SemanticRegistry.METRIC_SALES_VOLUME -> round(groupRows.stream()
                    .mapToDouble(row -> asDouble(row.get("quantity"))).sum());
            default -> throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "指标 " + metricCode + " 无认证计算口径", "请从指标目录中选择认证口径");
        };
    }

    /** 明细宽表上计算订单级指标时按 order_id 去重，避免多行重复计入。 */
    private static List<Map<String, Object>> distinctOrders(List<Map<String, Object>> rows) {
        Set<Object> seen = new HashSet<>();
        List<Map<String, Object>> distinct = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object orderId = row.get("order_id");
            if (orderId != null && seen.add(orderId)) {
                distinct.add(row);
            }
        }
        return distinct;
    }

    private static double netRevenue(List<Map<String, Object>> orders) {
        return round(orders.stream()
                .filter(MetricEvaluator::isPaidOrRefunded)
                .mapToDouble(row -> asDouble(row.get("pay_amount")) - asDouble(row.get("refund_amount")))
                .sum());
    }

    /** 按明细金额占比分摊订单净营收；只在最后统一四舍五入，避免逐行舍入误差。 */
    private static double apportionedNet(List<Map<String, Object>> rows,
                                         Map<Object, Double> orderItemTotals) {
        double sum = 0.0;
        for (Map<String, Object> row : rows) {
            if (!isPaidOrRefunded(row)) {
                continue;
            }
            double orderTotal = orderItemTotals.getOrDefault(row.get("order_id"), 0.0);
            if (orderTotal <= 0.0) {
                continue;
            }
            double orderNet = asDouble(row.get("pay_amount")) - asDouble(row.get("refund_amount"));
            sum += orderNet * asDouble(row.get("item_amount")) / orderTotal;
        }
        return round(sum);
    }

    private static double orderCount(List<Map<String, Object>> orders) {
        return orders.stream().filter(row -> !"cancelled".equals(text(row.get("order_status")))).count();
    }

    private static boolean isPaidOrRefunded(Map<String, Object> row) {
        String status = text(row.get("order_status"));
        return "paid".equals(status) || "refunded".equals(status);
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
