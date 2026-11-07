package com.queryon.web.dto;

/** @param correctedMetric 纠正后应命中的受管指标编码 @param note 纠正说明 */
public record FeedbackRequest(String question, String correctedMetric, String note) {
}
