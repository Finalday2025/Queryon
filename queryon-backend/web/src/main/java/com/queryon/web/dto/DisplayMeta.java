package com.queryon.web.dto;

import java.util.Map;

/**
 * 展示元数据：列编码 → 中文业务名、指标编码 → 单位、维度编码 → 下一层维度。
 * 供结果表、报表与交叉表统一渲染，不改动 ResultSet 的物理列名，跨入口口径不受影响。
 */
public record DisplayMeta(Map<String, String> columnLabels,
                          Map<String, String> metricUnits,
                          Map<String, String> drillDown) {
}
