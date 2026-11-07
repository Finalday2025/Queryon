package com.queryon.web.dto;

import java.util.List;

/** 同一语义查询在不同方言下的 SQL 快照与所用指标版本，供按方言分组回归与结论溯源。 */
public record DialectTranslation(String dialect, String sql, List<String> metricVersions) {
}
