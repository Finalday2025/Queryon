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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布门禁：评测不过即构建失败（-Prelease clean verify）。
 * 断言只写可校验的语义约束（全样本通过、每个方言分组无失败样本、样本数覆盖 24 条），
 * 不引入未经核实的数值 KPI；同时把确定性报告写入 target 供 CI 归档。
 */
class ReleaseGateTest {

    private EvaluationRunner runner() {
        AuditLog auditLog = new InMemoryAuditLog();
        QueryService queryService = new QueryService(SemanticRegistry.standard(),
                SampleDataGenerator.generate(LocalDate.now()), auditLog);
        AskService askService = new AskService(queryService, new DeterministicModelClient(),
                new ConversationMemory(), auditLog);
        return new EvaluationRunner(askService);
    }

    @Test
    void 评测全通过且每个方言分组均无失败样本() throws IOException {
        EvaluationReport report = runner().run();
        String text = ReportRenderer.render(report);

        Path target = Path.of("target", "evaluation-report.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);

        assertEquals(24, report.total(), "发布门禁须覆盖 24 条 golden eval set");
        assertFalse(report.byDialect().isEmpty(), "评测须按方言分组回归");
        report.byDialect().forEach((dialect, stat) -> assertEquals(stat.total(), stat.passed(),
                "方言 " + dialect + " 存在失败样本，阻断发布"));
        assertTrue(report.allPassed(), text);
    }
}
