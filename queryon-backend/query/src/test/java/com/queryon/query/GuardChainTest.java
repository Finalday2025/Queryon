package com.queryon.query;

import com.queryon.common.audit.AuditLog;
import com.queryon.common.audit.InMemoryAuditLog;
import com.queryon.common.context.Actor;
import com.queryon.common.context.AskTrace;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.query.data.SampleDataset;
import com.queryon.query.guard.GuardContext;
import com.queryon.query.guard.ParseGate;
import com.queryon.query.guard.SqlAstInspector;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import com.queryon.semantic.plan.ExecutionPlan;
import com.queryon.semantic.plan.PhysicalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 护栏单测：写操作、不存在字段、越权访问 100% 被拦截且不执行。 */
class GuardChainTest {

    private QueryService queryService;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        auditLog = new InMemoryAuditLog();
        SampleDataset dataset = SampleDataGenerator.generate(LocalDate.now());
        queryService = new QueryService(SemanticRegistry.standard(), dataset, auditLog);
    }

    @Test
    void 写操作语句被解析门拒绝() {
        QueryonException error = assertThrows(QueryonException.class,
                () -> new SqlAstInspector().inspect("DELETE FROM fact_order WHERE order_id = 'ORD-00001'"));
        assertEquals(ErrorCode.WRITE_OPERATION_REJECT.code(), error.error().code());
        assertEquals("解析门", error.error().gate());
    }

    @Test
    void 多语句被解析门拒绝() {
        QueryonException error = assertThrows(QueryonException.class,
                () -> new SqlAstInspector().inspect("SELECT 1; DROP TABLE fact_order"));
        assertEquals(ErrorCode.PARSE_GATE_REJECT.code(), error.error().code());
    }

    @Test
    void 非白名单函数被解析门拒绝() {
        ExecutionPlan plan = new ExecutionPlan("fact_order", List.of("fact_order"), List.of(),
                List.of(), List.of(), List.of(), 100, null, false);
        PhysicalQuery physical = new PhysicalQuery(
                "SELECT PG_SLEEP(10) AS net_revenue FROM fact_order", plan);
        GuardContext context = new GuardContext(physical, SemanticQuery.empty(), Actor.adminBi(),
                new AskTrace(), emptyLink(), 10);
        QueryonException error = assertThrows(QueryonException.class, () -> new ParseGate().check(context));
        assertEquals(ErrorCode.PARSE_GATE_REJECT.code(), error.error().code());
    }

    @Test
    void 不存在字段被Schema门拒绝() {
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(List.of(SemanticRegistry.METRIC_NET_REVENUE))
                .withTimeWindow(TimeWindow.lastMonth(LocalDate.now()))
                .withFilter("member_score", "1");
        QueryonException error = assertThrows(QueryonException.class,
                () -> queryService.execute(query, Actor.adminBi()));
        assertEquals(ErrorCode.SCHEMA_GATE_REJECT.code(), error.error().code());
    }

    @Test
    void 越权敏感列被权限注入拦截且不执行() {
        SchemaLinkResult link = new SchemaLinkResult(List.of(), List.of(), List.of("cost"),
                List.of("成本价"), List.of(), List.of());
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(List.of(SemanticRegistry.METRIC_NET_REVENUE));
        QueryonException error = assertThrows(QueryonException.class,
                () -> queryService.execute(query, Actor.analystEast(), link));
        assertEquals(ErrorCode.PERMISSION_DENIED.code(), error.error().code());
        assertTrue(auditLog.events().stream().anyMatch(event -> "拦截".equals(event.outcome())));
    }

    @Test
    void 行数上限被超限时执行门拒绝() {
        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(List.of(SemanticRegistry.METRIC_ORDER_COUNT))
                .withLimit(5000);
        QueryonException error = assertThrows(QueryonException.class,
                () -> queryService.execute(query, Actor.adminBi()));
        assertEquals(ErrorCode.EXECUTION_GATE_REJECT.code(), error.error().code());
    }

    private static SchemaLinkResult emptyLink() {
        return new SchemaLinkResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
