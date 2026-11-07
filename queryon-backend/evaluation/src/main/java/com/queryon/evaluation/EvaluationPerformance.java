package com.queryon.evaluation;

import java.util.Map;

/**
 * 评测运行期性能观测（`D6-08` 的延迟部分）。
 *
 * <p>刻意与 {@link EvaluationReport} 分离：报告要求相同输入可重复生成、不含时间戳，
 * 而耗时每次运行都不同，因此性能观测不进入报告、不参与发布门禁比较，只作趋势参考。
 * 成本维度需真实模型接入后才能计量（`05` 第 10 节未决第 1 项），当前标记为待启用。
 *
 * @param averageMsByDialect 按方言的平均耗时，用于判断哪条方言路径更慢
 */
public record EvaluationPerformance(String version, int totalCases, long totalElapsedMs,
                                    long averageElapsedMs, long p95ElapsedMs,
                                    Map<String, Long> averageMsByDialect,
                                    String slowestCase, long slowestMs,
                                    String costStatus, String note) {

    public EvaluationPerformance {
        averageMsByDialect = Map.copyOf(averageMsByDialect);
    }
}
