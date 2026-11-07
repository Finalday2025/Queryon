package com.queryon.evaluation;

import com.queryon.ai.AskService;
import com.queryon.ai.adapter.DeterministicModelClient;
import com.queryon.ai.conversation.ConversationMemory;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.audit.InMemoryAuditLog;
import com.queryon.query.QueryService;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.semantic.SemanticRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 评测即测试套件：24 条 golden set 一键回归、报告可重复、纠正样本可回流复测。 */
class GoldenSetRegressionTest {

    private AskService askService() {
        AuditLog auditLog = new InMemoryAuditLog();
        QueryService queryService = new QueryService(SemanticRegistry.standard(),
                SampleDataGenerator.generate(LocalDate.now()), auditLog);
        return new AskService(queryService, new DeterministicModelClient(),
                new ConversationMemory(), auditLog);
    }

    private EvaluationRunner runner() {
        return new EvaluationRunner(askService());
    }

    @Test
    void 二十四条样本全部通过语义约束断言() {
        EvaluationReport report = runner().run();
        assertEquals(24, report.total());
        assertEquals(24, report.byScene().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(report.byDialect().containsKey(GoldenSet.DIALECT_PG));
        assertTrue(report.allPassed(), ReportRenderer.render(report));
    }

    @Test
    void 报告可重复生成() {
        EvaluationRunner runner = runner();
        assertEquals(ReportRenderer.render(runner.run()), ReportRenderer.render(runner.run()));
    }

    @Test
    void 纠正样本回流后可复测() {
        FeedbackStore feedbackStore = new FeedbackStore();
        feedbackStore.record("上月净营收是多少", "net_revenue", "人工确认认证口径");
        EvaluationReport report = new EvaluationRunner(askService(), feedbackStore).run();
        assertEquals(25, report.total());
        assertTrue(report.allPassed(), ReportRenderer.render(report));
    }
}
