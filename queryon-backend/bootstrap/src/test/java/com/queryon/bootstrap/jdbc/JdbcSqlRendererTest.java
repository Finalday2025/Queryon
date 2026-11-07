package com.queryon.bootstrap.jdbc;

import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.SemanticTranslator;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import com.queryon.semantic.plan.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC 侧金额分摊 SQL 渲染回归；纯单元，不需要连接数据库。 */
class JdbcSqlRendererTest {

    private final SemanticRegistry registry = SemanticRegistry.standard();
    private final SemanticTranslator translator = new SemanticTranslator(registry);
    private final JdbcSqlRenderer renderer = new JdbcSqlRenderer();

    private ExecutionPlan plan(List<String> metrics, List<String> dimensions) {
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(metrics)
                .withDimensions(dimensions)
                .withTimeWindow(TimeWindow.all())
                .withLimit(50);
        return translator.translate(query).plan();
    }

    @Test
    void 明细基表时金额指标按明细金额占比分摊() {
        String sql = renderer.render(plan(List.of("net_revenue"), List.of("category")));
        assertTrue(sql.contains("i.item_amount / NULLIF(ot.order_item_total, 0)"),
                "金额应按明细金额占比分摊");
        assertTrue(sql.contains("SUM(item_amount) AS order_item_total"),
                "分摊分母应来自全量订单明细金额派生表");
        assertTrue(sql.contains("GROUP BY p.category"));
    }

    @Test
    void 订单基表时不分摊且保持直接累加() {
        String sql = renderer.render(plan(List.of("net_revenue"), List.of("sales_territory")));
        assertFalse(sql.contains("order_item_total"), "订单主表上无需分摊");
        assertTrue(sql.contains("o.pay_amount - o.refund_amount"));
    }

    @Test
    void 客单价分子同样使用分摊后的净营收() {
        String sql = renderer.render(plan(List.of("avg_order_value"), List.of("brand")));
        assertTrue(sql.contains("order_item_total"));
        assertTrue(sql.contains("NULLIF(COUNT(DISTINCT"));
    }

    @Test
    void 渲染结果确定可重复() {
        ExecutionPlan plan = plan(List.of("net_revenue", "sales_volume"), List.of("category"));
        assertEquals(renderer.render(plan), renderer.render(plan),
                "同一执行计划应渲染出完全相同的 SQL，缓存键才稳定");
    }
}
