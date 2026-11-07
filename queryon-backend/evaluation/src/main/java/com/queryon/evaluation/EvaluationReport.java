package com.queryon.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 可对比的版本报告。不含时间戳，相同输入可重复生成；
 * 不引用外部厂商披露的准确率数字作为 KPI。
 *
 * @param groupStat 分组统计：总数／通过数／准确率
 */
public record EvaluationReport(String version, int total, int passed, int failed,
                               double parsePassRate, double executionAccuracy,
                               int guardInterceptions, Map<String, GroupStat> byDialect,
                               Map<String, Integer> byScene, List<CaseResult> cases) {

    public record GroupStat(int total, int passed, double accuracy) {
    }

    public EvaluationReport {
        byDialect = Map.copyOf(byDialect);
        byScene = Map.copyOf(byScene);
        cases = List.copyOf(cases);
    }

    public boolean allPassed() {
        return failed == 0;
    }
}
