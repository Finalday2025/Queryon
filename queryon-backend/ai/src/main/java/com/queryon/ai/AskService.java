package com.queryon.ai;

import com.queryon.ai.adapter.ChatModelPort;
import com.queryon.ai.conversation.ConversationMemory;
import com.queryon.ai.parser.HybridQuestionParser;
import com.queryon.ai.parser.UnderstandingResult;
import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.context.Actor;
import com.queryon.common.context.AskTrace;
import com.queryon.common.observability.QueryMetrics;
import com.queryon.common.error.QueryonException;
import com.queryon.query.QueryOutcome;
import com.queryon.query.QueryService;
import com.queryon.semantic.model.MetricDefinition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 问数主链路 8 步编排：提问（携带身份）→ 上下文补全 → 语义检索 → 生成语义查询
 * （歧义则澄清）→ 四道护栏 → 按提问者权限执行 → 结果／图表／口径 → 反馈沉淀。
 * 每一步留下可观测记录，且不记录原始问句与 prompt 内容。
 */
public final class AskService {

    private final HybridQuestionParser parser;
    private final QueryService queryService;
    private final ConversationMemory memory;
    private final AuditLog auditLog;
    private final QueryMetrics metrics;

    public AskService(QueryService queryService, ChatModelPort modelPort,
                      ConversationMemory memory, AuditLog auditLog) {
        this(queryService, modelPort, memory, auditLog, QueryMetrics.NOOP);
    }

    public AskService(QueryService queryService, ChatModelPort modelPort,
                      ConversationMemory memory, AuditLog auditLog, QueryMetrics metrics) {
        this.queryService = queryService;
        this.memory = memory;
        this.auditLog = auditLog;
        this.metrics = metrics == null ? QueryMetrics.NOOP : metrics;
        this.parser = new HybridQuestionParser(queryService.registry(), modelPort, memory);
    }

    public AskResponse ask(String question, Actor actor, String sessionId) {
        String sid = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        long start = System.nanoTime();
        AskTrace trace = new AskTrace();
        trace.record("提问", "身份=" + actor.role());
        UnderstandingResult understanding = parser.parse(question, sid, actor, LocalDate.now());
        trace.record("上下文补全", "会话=" + sid + "，多轮继承已应用");
        trace.record("语义检索", "命中术语=" + understanding.link().matchedTerms().size()
                + "，候选指标=" + understanding.link().metricCodes().size());
        trace.record("生成", "解析路径=" + understanding.kind());

        return switch (understanding.kind()) {
            case REJECTED -> {
                // 解析门等前置拦截不经过 QueryService，这里单独计入同一份指标，
                // 保证「护栏拦截统计」覆盖四道门的全部拦截点。
                metrics.record((System.nanoTime() - start) / 1_000_000,
                        false, understanding.error().code(), 0);
                audit(trace.traceId(), actor, "问数", "拦截", understanding.error().code());
                yield AskResponse.rejected(question, understanding.error(), trace.traceId(), trace.steps());
            }
            case CLARIFICATION -> {
                audit(trace.traceId(), actor, "问数", "澄清", "歧义或口径不确定");
                yield AskResponse.clarification(question, understanding.clarification(),
                        trace.traceId(), trace.steps());
            }
            case DEFINITION -> {
                audit(trace.traceId(), actor, "问数", "口径说明", "展示认证口径定义");
                yield AskResponse.definition(question, understanding.definitionNotes(),
                        trace.traceId(), trace.steps());
            }
            case QUERY -> execute(question, actor, sid, understanding, trace);
        };
    }

    private AskResponse execute(String question, Actor actor, String sessionId,
                                UnderstandingResult understanding, AskTrace trace) {
        try {
            QueryOutcome outcome = queryService.execute(understanding.query(), actor, understanding.link());
            memory.remember(sessionId, understanding.query());
            List<AskTrace.Step> steps = new ArrayList<>(trace.steps());
            steps.addAll(outcome.trace().steps());
            List<String> definitions = outcome.metricDefinitions().stream()
                    .map(AskService::formatDefinition).toList();
            String chartType = understanding.query().dimensions().isEmpty() ? "metric" : "bar";
            audit(outcome.trace().traceId(), actor, "问数", "成功", "结果行数=" + outcome.resultSet().rows().size());
            return new AskResponse(AskResponse.Status.ANSWERED, question, understanding.query(),
                    outcome.resultSet(), chartType, outcome.sql(), definitions, null, null,
                    outcome.trace().traceId(), steps,
                    ResultInterpreter.interpret(outcome.resultSet()), ResultInterpreter.suggestions());
        } catch (QueryonException e) {
            trace.record("护栏", e.error().gate() + "：" + e.error().code() + " 拦截");
            audit(trace.traceId(), actor, "问数", "拦截", e.error().gate() + "／" + e.error().code());
            return AskResponse.rejected(question, e.error(), trace.traceId(), trace.steps());
        }
    }

    private static String formatDefinition(MetricDefinition metric) {
        return metric.businessName() + "：" + metric.definition()
                + "｜公式 " + metric.expression()
                + "｜过滤 " + metric.defaultFilter()
                + "｜单位 " + metric.unit()
                + "｜版本 " + metric.version();
    }

    private void audit(String traceId, Actor actor, String action, String outcome, String detail) {
        auditLog.record(new AuditEvent(traceId, actor.userId(), actor.role(), action, outcome, detail));
    }

    public QueryService queryService() {
        return queryService;
    }

    public ConversationMemory memory() {
        return memory;
    }

    public AuditLog auditLog() {
        return auditLog;
    }
}
