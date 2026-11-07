package com.queryon.evaluation;

import java.util.Locale;

/** 把评测报告渲染为确定性文本；不含时间戳，相同输入可重复生成。 */
public final class ReportRenderer {

    private ReportRenderer() {
    }

    public static String render(EvaluationReport report) {
        StringBuilder text = new StringBuilder();
        text.append("Queryon golden eval set 回归报告\n")
                .append("版本：").append(report.version()).append("\n")
                .append("样本数：").append(report.total())
                .append("，通过：").append(report.passed())
                .append("，失败：").append(report.failed()).append("\n")
                .append("解析通过率：").append(percent(report.parsePassRate()))
                .append("，执行准确率：").append(percent(report.executionAccuracy())).append("\n")
                .append("护栏拦截次数：").append(report.guardInterceptions()).append("\n\n");

        text.append("按方言分组\n");
        report.byDialect().forEach((dialect, stat) -> text.append("- ").append(dialect)
                .append("：").append(stat.passed()).append("/").append(stat.total())
                .append("，准确率 ").append(percent(stat.accuracy())).append("\n"));
        text.append("\n按场景分组\n");
        report.byScene().forEach((scene, count) -> text.append("- ").append(scene)
                .append("：").append(count).append(" 条\n"));

        text.append("\n逐条结果\n");
        for (CaseResult result : report.cases()) {
            text.append("- ").append(result.id()).append(" [").append(result.scene())
                    .append("/").append(result.role()).append("] ")
                    .append(result.passed() ? "通过" : "失败")
                    .append("｜").append(result.question())
                    .append("｜").append(result.status())
                    .append("｜").append(result.fingerprint()).append("\n");
            for (String failure : result.failures()) {
                text.append("    - ").append(failure).append("\n");
            }
        }
        text.append("\n结论：").append(report.allPassed()
                ? "全部样本通过，满足发布门禁" : "存在失败样本，阻断发布").append("\n");
        return text.toString();
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100);
    }
}
