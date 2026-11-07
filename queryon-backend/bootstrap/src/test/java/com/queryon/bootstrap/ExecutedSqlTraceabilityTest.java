package com.queryon.bootstrap;

import com.queryon.bootstrap.jdbc.JdbcSqlRenderer;
import com.queryon.common.audit.InMemoryAuditLog;
import com.queryon.common.context.Actor;
import com.queryon.common.result.ResultSet;
import com.queryon.query.QueryOutcome;
import com.queryon.query.QueryService;
import com.queryon.query.data.SampleDataset;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.query.executor.InMemoryQueryExecutor;
import com.queryon.query.executor.QueryExecutor;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import com.queryon.semantic.plan.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 溯源口径（`G5`）：接口返回的 {@code sql} 必须是执行器实际执行的语句，
 * 而不是另一份只用于 AST 校验的语义快照；按商品维度时需要能看到分摊逻辑。
 */
class ExecutedSqlTraceabilityTest {

    private final SemanticRegistry registry = SemanticRegistry.standard();
    private final SampleDataset dataset = SampleDataGenerator.generate(LocalDate.now());
    private final JdbcSqlRenderer renderer = new JdbcSqlRenderer();

    @Test
    void 展示的SQL与实际执行语句完全一致() {
        QueryService service = serviceWith(new RenderingExecutor(dataset, renderer));
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(List.of("net_revenue", "order_count"))
                .withDimensions(List.of("sales_territory"))
                .withTimeWindow(TimeWindow.all())
                .withLimit(20)
                .withFilter("sales_territory", "华东");

        QueryOutcome outcome = service.execute(query, Actor.adminBi());

        assertEquals(renderer.render(outcome.plan()), outcome.sql(),
                "对外展示的 SQL 必须就是执行器渲染并执行的语句");
        assertTrue(outcome.sql().contains("r.sales_territory"), "执行 SQL 应带表别名限定");
        assertTrue(outcome.sql().contains("o.pay_amount"), "金额列应限定到订单主表别名");
        assertFalse(outcome.sql().contains("SELECT sales_territory AS sales_territory"),
                "不得再返回未限定别名的语义快照");
        assertEquals(1, outcome.resultSet().rows().size(), "过滤条件应生效");
    }

    @Test
    void 商品维度分摊逻辑出现在展示SQL中() {
        QueryService service = serviceWith(new RenderingExecutor(dataset, renderer));
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(List.of("net_revenue"))
                .withDimensions(List.of("category"))
                .withTimeWindow(TimeWindow.all())
                .withLimit(20);

        QueryOutcome outcome = service.execute(query, Actor.adminBi());

        assertTrue(outcome.sql().contains("item_amount / NULLIF(ot.order_item_total, 0)"),
                "按商品维度时，展示 SQL 必须体现明细分摊，否则与执行结果口径不一致");
        assertTrue(outcome.sql().contains("JOIN dim_product p"));
        assertTrue(outcome.resultSet().rows().size() > 0);
    }

    private QueryService serviceWith(QueryExecutor executor) {
        return new QueryService(registry, dataset, new InMemoryAuditLog(), null, executor);
    }

    /** 内存取数 + JDBC 渲染：只验证 SQL 溯源，不需要真实数据库。 */
    private static final class RenderingExecutor implements QueryExecutor {

        private final InMemoryQueryExecutor delegate;
        private final JdbcSqlRenderer renderer;

        private RenderingExecutor(SampleDataset dataset, JdbcSqlRenderer renderer) {
            this.delegate = new InMemoryQueryExecutor(dataset);
            this.renderer = renderer;
        }

        @Override
        public ResultSet execute(ExecutionPlan plan) {
            return delegate.execute(plan);
        }

        @Override
        public long estimateRowCount(String table) {
            return delegate.estimateRowCount(table);
        }

        @Override
        public String renderedSql(ExecutionPlan plan) {
            return renderer.render(plan);
        }
    }
}
