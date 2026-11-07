package com.queryon.evaluation;

import com.queryon.ai.AskResponse;
import com.queryon.ai.AskService;
import com.queryon.common.context.Actor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * golden eval set 一键回归运行器。评测直接驱动真实问数主链路，
 * 与线上入口共用同一套语义层、四道护栏与权限注入，不另建解析路径。
 */
public final class EvaluationRunner {

    private final AskService askService;
    private final FeedbackStore feedbackStore;

    /** 最近一次运行的性能观测；不写入可重复报告，避免破坏报告确定性。 */
    private volatile EvaluationPerformance lastPerformance;

    public EvaluationRunner(AskService askService) {
        this(askService, null);
    }

    public EvaluationRunner(AskService askService, FeedbackStore feedbackStore) {
        this.askService = askService;
        this.feedbackStore = feedbackStore;
    }

    public EvaluationPerformance lastPerformance() {
        return lastPerformance;
    }

    public EvaluationReport run() {
        List<GoldenCase> cases = new ArrayList<>(GoldenSet.cases());
        if (feedbackStore != null) {
            cases.addAll(feedbackStore.asCases());
        }
        List<CaseResult> results = new ArrayList<>();
        List<Long> elapsedValues = new ArrayList<>();
        Map<String, List<Long>> elapsedByDialect = new LinkedHashMap<>();
        String slowestCase = "";
        long slowestMs = 0;
        int answeredExpected = 0;
        int answeredPassed = 0;
        int interceptions = 0;

        for (GoldenCase testCase : cases) {
            Actor actor = Actor.ROLE_ANALYST_EAST.equals(testCase.role())
                    ? Actor.analystEast() : Actor.adminBi();
            String sessionId = "golden-" + testCase.id();
            for (String contextQuestion : testCase.contextQuestions()) {
                askService.ask(contextQuestion, actor, sessionId);
            }
            long startNanos = System.nanoTime();
            AskResponse response = askService.ask(testCase.question(), actor, sessionId);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            elapsedValues.add(elapsedMs);
            elapsedByDialect.computeIfAbsent(testCase.dialect(), key -> new ArrayList<>())
                    .add(elapsedMs);
            if (elapsedMs > slowestMs) {
                slowestMs = elapsedMs;
                slowestCase = String.valueOf(testCase.id());
            }
            List<String> failures = check(testCase, response);
            boolean passed = failures.isEmpty();
            boolean expectsAnswer = testCase.expectations().stream()
                    .anyMatch(expectation -> expectation.type() == Expectation.Type.ANSWERED);
            if (expectsAnswer) {
                answeredExpected++;
                if (response.status() == AskResponse.Status.ANSWERED) {
                    answeredPassed++;
                }
            }
            if (response.status() == AskResponse.Status.REJECTED) {
                interceptions++;
            }
            String fingerprint = response.status() == AskResponse.Status.ANSWERED
                    ? response.semanticQuery().fingerprint()
                    : (response.error() == null ? "clarification/definition" : response.error().code());
            results.add(new CaseResult(testCase.id(), testCase.question(), testCase.scene(),
                    testCase.role(), testCase.dialect(), response.status().name(),
                    fingerprint, passed, failures));
        }

        int passed = (int) results.stream().filter(CaseResult::passed).count();
        int total = results.size();
        double parsePassRate = answeredExpected == 0 ? 1.0
                : (double) answeredPassed / answeredExpected;
        double executionAccuracy = total == 0 ? 0.0 : (double) passed / total;

        Map<String, EvaluationReport.GroupStat> byDialect = new LinkedHashMap<>();
        for (String dialect : results.stream().map(CaseResult::dialect).distinct().toList()) {
            List<CaseResult> group = results.stream().filter(result -> result.dialect().equals(dialect)).toList();
            long groupPassed = group.stream().filter(CaseResult::passed).count();
            byDialect.put(dialect, new EvaluationReport.GroupStat(group.size(), (int) groupPassed,
                    group.isEmpty() ? 0.0 : (double) groupPassed / group.size()));
        }
        Map<String, Integer> byScene = new LinkedHashMap<>();
        for (CaseResult result : results) {
            byScene.merge(result.scene(), 1, Integer::sum);
        }
        lastPerformance = buildPerformance(results.size(), elapsedValues, elapsedByDialect,
                slowestCase, slowestMs);
        return new EvaluationReport(GoldenSet.VERSION, total, passed, total - passed,
                parsePassRate, executionAccuracy, interceptions, byDialect, byScene, results);
    }

    private static EvaluationPerformance buildPerformance(int totalCases, List<Long> elapsed,
                                                          Map<String, List<Long>> byDialect,
                                                          String slowestCase, long slowestMs) {
        long totalMs = 0;
        for (long value : elapsed) {
            totalMs += value;
        }
        long averageMs = elapsed.isEmpty() ? 0 : totalMs / elapsed.size();
        Map<String, Long> dialectAverage = new LinkedHashMap<>();
        byDialect.forEach((dialect, values) -> {
            long sum = 0;
            for (long value : values) {
                sum += value;
            }
            dialectAverage.put(dialect, values.isEmpty() ? 0 : sum / values.size());
        });
        return new EvaluationPerformance(GoldenSet.VERSION, totalCases, totalMs, averageMs,
                percentile(elapsed, 0.95), dialectAverage, slowestCase, slowestMs,
                "PENDING_REAL_MODEL",
                "延迟为本次运行观测，不进入可重复报告与门禁比较；成本维度待真实模型接入（05 第 10 节未决）");
    }

    private static long percentile(List<Long> values, double ratio) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(sorted.size() * ratio) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static List<String> check(GoldenCase testCase, AskResponse response) {
        List<String> failures = new ArrayList<>();
        for (Expectation expectation : testCase.expectations()) {
            String expected = expectation.values().isEmpty() ? "" : expectation.values().getFirst();
            switch (expectation.type()) {
                case ANSWERED -> require(response.status() == AskResponse.Status.ANSWERED,
                        failures, expectation, "实际状态=" + response.status());
                case CLARIFICATION -> require(response.status() == AskResponse.Status.CLARIFICATION,
                        failures, expectation, "实际状态=" + response.status());
                case DEFINITION -> require(response.status() == AskResponse.Status.DEFINITION,
                        failures, expectation, "实际状态=" + response.status());
                case REJECTED -> require(response.status() == AskResponse.Status.REJECTED,
                        failures, expectation, "实际状态=" + response.status());
                case METRIC -> require(response.semanticQuery().metrics().contains(expected),
                        failures, expectation, "实际指标=" + response.semanticQuery().metrics());
                case DIMENSION -> require(response.semanticQuery().dimensions().contains(expected),
                        failures, expectation, "实际维度=" + response.semanticQuery().dimensions());
                case TIME_WINDOW -> require(response.semanticQuery().timeWindow().type().name().equals(expected),
                        failures, expectation, "实际时间窗=" + response.semanticQuery().timeWindow().type());
                case ROW_FILTER -> require(expected.equals(
                                response.semanticQuery().filters().get("sales_territory")),
                        failures, expectation, "实际过滤=" + response.semanticQuery().filters());
                case ROW_SCOPE -> checkRowScope(expected, response, expectation, failures);
                case TOP_N -> require(response.semanticQuery().limit() == Integer.parseInt(expected)
                                && response.resultSet().rows().size() <= Integer.parseInt(expected),
                        failures, expectation, "实际上限=" + response.semanticQuery().limit()
                                + "，实际行数=" + response.resultSet().rows().size());
                case ERROR_GATE -> require(response.error() != null && expected.equals(response.error().gate()),
                        failures, expectation, "实际拦截门="
                                + (response.error() == null ? "无" : response.error().gate()));
                case DEFINITION_TEXT -> require(response.metricDefinitions().stream()
                                .anyMatch(note -> note.contains(expected)),
                        failures, expectation, "口径说明未包含关键词 " + expected);
            }
        }
        return failures;
    }

    private static void checkRowScope(String expected, AskResponse response,
                                      Expectation expectation, List<String> failures) {
        if (response.status() != AskResponse.Status.ANSWERED) {
            failures.add(expectation.description() + "：未返回结果");
            return;
        }
        if (!response.resultSet().columns().contains("sales_territory")) {
            failures.add(expectation.description() + "：结果缺少大区列");
            return;
        }
        if (response.resultSet().rows().isEmpty()) {
            failures.add(expectation.description() + "：结果为空，行级权限可能被过度收窄");
            return;
        }
        for (Map<String, Object> row : response.resultSet().rows()) {
            if (!expected.equals(String.valueOf(row.get("sales_territory")))) {
                failures.add(expectation.description() + "：出现越权大区 "
                        + row.get("sales_territory"));
                return;
            }
        }
    }

    private static void require(boolean condition, List<String> failures,
                                Expectation expectation, String actual) {
        if (!condition) {
            failures.add(expectation.description() + "（" + expectation.type() + "）：" + actual);
        }
    }
}
