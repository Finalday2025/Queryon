package com.queryon.evaluation;

/** 人工纠正样本：只记录受管指标编码与说明，不记录凭据或敏感内容。 */
public record FeedbackEntry(String question, String correctedMetric, String note,
                            String versionSource) {
}
