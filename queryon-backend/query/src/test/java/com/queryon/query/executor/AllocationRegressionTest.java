package com.queryon.query.executor;

import com.queryon.common.result.ResultSet;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.SemanticTranslator;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import com.queryon.semantic.plan.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金额分摊口径回归（05 第 8.3 节）：按商品/品类维度或含销量指标拆解时，
 * 各分组净营收之和必须等于不分组的订单口径总额；订单数与销量仍按各自认证口径。
 * 断言只写可校验的语义约束，不固化具体数值 KPI。
 */
class AllocationRegressionTest {

    private final SemanticRegistry registry = SemanticRegistry.standard();
    private final SemanticTranslator translator = new SemanticTranslator(registry);
    private final SampleDataset dataset = SampleDataGenerator.generate(LocalDate.now());
    private final InMemoryQueryExecutor executor = new InMemoryQueryExecutor(dataset);

    private ResultSet run(List<String> metrics, List<String> dimensions) {
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(metrics)
                .withDimensions(dimensions)
                .withTimeWindow(TimeWindow.all())
                .withLimit(1000);
        ExecutionPlan plan = translator.translate(query).plan();
        return executor.execute(plan);
    }

    private static double sum(ResultSet resultSet, String column) {
        return resultSet.rows().stream().mapToDouble(row -> {
            Object value = row.get(column);
            return value instanceof Number number ? number.doubleValue() : 0.0;
        }).sum();
    }

    @Test
    void 按品类拆解净营收之和等于订单口径总额() {
        double total = sum(run(List.of("net_revenue"), List.of()), "net_revenue");
        double byCategory = sum(run(List.of("net_revenue"), List.of("category")), "net_revenue");
        assertTrue(total > 0, "示例数据应产生正的净营收");
        assertEquals(total, byCategory, 0.05,
                "按品类拆解时订单金额不得因一对多明细而重复计入或丢失");
    }

    @Test
    void 按商品拆解净营收之和等于订单口径总额() {
        double total = sum(run(List.of("net_revenue"), List.of()), "net_revenue");
        double byProduct = sum(run(List.of("net_revenue"), List.of("product_name")), "net_revenue");
        assertEquals(total, byProduct, 0.05);
    }

    @Test
    void 含销量指标时净营收同样分摊() {
        double total = sum(run(List.of("net_revenue"), List.of()), "net_revenue");
        ResultSet resultSet = run(List.of("net_revenue", "sales_volume"),
                List.of("sales_territory"));
        assertEquals(total, sum(resultSet, "net_revenue"), 0.05);
        assertTrue(sum(resultSet, "sales_volume") > 0, "销量应仍为明细行件数之和");
    }

    @Test
    void 按销售大区拆解净营收之和仍等于订单口径总额() {
        double total = sum(run(List.of("net_revenue"), List.of()), "net_revenue");
        double byRegion = sum(run(List.of("net_revenue"), List.of("sales_territory")),
                "net_revenue");
        assertEquals(total, byRegion, 0.05);
    }

    @Test
    void 订单数不因分摊而改变认证口径() {
        double totalOrders = sum(run(List.of("order_count"), List.of()), "order_count");
        double byCategory = sum(run(List.of("order_count"), List.of("category")),
                "order_count");
        assertTrue(totalOrders > 0);
        assertTrue(byCategory >= totalOrders,
                "同一订单可包含多个品类，分组订单数之和不应少于去重订单总数");
    }

    @Test
    void 客单价分子使用分摊后的净营收() {
        ResultSet resultSet = run(List.of("net_revenue", "order_count", "avg_order_value"),
                List.of("category"));
        for (Map<String, Object> row : resultSet.rows()) {
            double net = ((Number) row.get("net_revenue")).doubleValue();
            double count = ((Number) row.get("order_count")).doubleValue();
            double avg = ((Number) row.get("avg_order_value")).doubleValue();
            if (count > 0) {
                assertEquals(Math.round(net / count * 100.0) / 100.0, avg, 0.05);
            }
        }
    }
}
