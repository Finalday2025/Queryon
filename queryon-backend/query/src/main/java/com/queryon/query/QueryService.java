package com.queryon.query;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.context.Actor;
import com.queryon.common.context.AskTrace;
import com.queryon.common.error.QueryonException;
import com.queryon.common.observability.ObservationStore;
import com.queryon.common.result.ResultSet;
import com.queryon.query.data.SampleDataset;
import com.queryon.query.executor.InMemoryQueryExecutor;
import com.queryon.query.executor.QueryExecutor;
import com.queryon.query.guard.GuardChain;
import com.queryon.query.guard.GuardContext;
import com.queryon.query.permission.PermissionInjector;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.SemanticTranslator;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.plan.PhysicalQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * 受治理查询的唯一编排入口：身份 → 权限注入 → 语义翻译 → 四道护栏 → 执行 → 结果脱敏 → 审计。
 * 问数、SQL 编辑器、仪表板、开放 API、MCP、数据集复用与评测都调用它，
 * 因此口径、权限与可观测埋点不会分裂，也不会有入口旁路。
 * 执行器由外部注入（内存示例数据集或 PostgreSQL），切换数据源不改变任何治理逻辑。
 */
public final class QueryService {

    private static final SchemaLinkResult EMPTY_LINK =
            new SchemaLinkResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private final SemanticRegistry registry;
    private final SemanticTranslator translator;
    private final GuardChain guardChain;
    private final PermissionInjector injector;
    private final QueryExecutor executor;
    private final SampleDataset dataset;
    private final AuditLog auditLog;
    private final ObservationStore observations;

    public QueryService(SemanticRegistry registry, SampleDataset dataset, AuditLog auditLog) {
        this(registry, dataset, auditLog, null, new InMemoryQueryExecutor(dataset));
    }

    public QueryService(SemanticRegistry registry, SampleDataset dataset, AuditLog auditLog,
                        ObservationStore observations) {
        this(registry, dataset, auditLog, observations, new InMemoryQueryExecutor(dataset));
    }

    /** 注入可观测存储与执行器；执行器为空时回退内存实现，便于纯单元测试保持零依赖。 */
    public QueryService(SemanticRegistry registry, SampleDataset dataset, AuditLog auditLog,
                        ObservationStore observations, QueryExecutor executor) {
        this.registry = registry;
        this.dataset = dataset;
        this.auditLog = auditLog;
        this.observations = observations;
        this.executor = executor == null ? new InMemoryQueryExecutor(dataset) : executor;
        this.translator = new SemanticTranslator(registry);
        this.guardChain = new GuardChain(registry);
        this.injector = new PermissionInjector();
    }

    public QueryOutcome execute(SemanticQuery query, Actor actor) {
        return execute(query, actor, EMPTY_LINK);
    }

    public QueryOutcome execute(SemanticQuery query, Actor actor, SchemaLinkResult link) {
        AskTrace trace = new AskTrace();
        long start = System.nanoTime();
        SchemaLinkResult effectiveLink = link == null ? EMPTY_LINK : link;
        trace.record("身份", "role=" + actor.role());
        try {
            SemanticQuery governed = injector.inject(query, actor, effectiveLink, trace);
            PhysicalQuery physical = translator.translate(governed);
            // 展示与审计使用执行器实际执行的 SQL；内存执行器无物理 SQL 时回退语义计划快照。
            // 护栏仍校验 translator 产出的计划快照，执行 SQL 由同一份 plan 确定性渲染，两者口径同源。
            String executedSql = executor.renderedSql(physical.plan());
            String displayedSql = executedSql == null || executedSql.isBlank()
                    ? physical.sql() : executedSql;
            trace.record("生成", "语义查询翻译完成，指标=" + governed.metrics()
                    + "，维度=" + governed.dimensions());
            GuardContext context = new GuardContext(physical, governed, actor, trace,
                    effectiveLink, executor.estimateRowCount(physical.plan().baseTable()));
            guardChain.check(context);
            ResultSet raw = executor.execute(physical.plan());
            ResultSet masked = injector.mask(raw, actor);
            trace.record("输出", "结果行数=" + masked.rows().size());

            List<MetricDefinition> definitions = new ArrayList<>();
            for (String code : governed.metrics()) {
                definitions.add(registry.requireMetric(code));
            }
            auditLog.record(new AuditEvent(trace.traceId(), actor.userId(), actor.role(),
                    "受治理查询", "成功", "指标=" + governed.metrics() + "，行数=" + masked.rows().size()));
            QueryOutcome outcome = new QueryOutcome(masked, displayedSql, physical.plan(),
                    definitions, trace);
            observe(trace, actor, true, null, masked.rows().size(), start);
            return outcome;
        } catch (QueryonException e) {
            trace.record("护栏", e.error().gate() + "：" + e.error().code() + " 拦截");
            auditLog.record(new AuditEvent(trace.traceId(), actor.userId(), actor.role(),
                    "受治理查询", "拦截", e.error().gate() + "／" + e.error().code()));
            observe(trace, actor, false, e.error().code(), 0, start);
            throw e;
        }
    }

    private void observe(AskTrace trace, Actor actor, boolean success, String errorCode,
                         int rows, long start) {
        if (observations == null) {
            return;
        }
        observations.record(new ObservationStore.Event(
                trace.traceId(), actor.userId(), actor.role(), "受治理查询",
                (System.nanoTime() - start) / 1_000_000, success, errorCode, rows,
                trace.steps().stream().map(step -> step.stage() + "：" + step.detail()).toList()));
    }

    public SemanticRegistry registry() {
        return registry;
    }

    public SampleDataset dataset() {
        return dataset;
    }

    public AuditLog auditLog() {
        return auditLog;
    }
}
