package com.queryon.semantic.model;

import java.util.List;

/**
 * 受管指标定义：每个歧义指标只有一个认证口径（05 第 8.3 节）。
 * 公式列是口径表达，不是可直接执行的完整 SQL。
 *
 * @param code           指标编码
 * @param businessName   中文业务名
 * @param definition     唯一认证口径
 * @param expression     公式／过滤说明
 * @param defaultFilter  指标级默认过滤
 * @param unit           单位
 * @param aliases        可命中该指标的规范别名
 * @param version        口径版本
 */
public record MetricDefinition(String code, String businessName, String definition,
                               String expression, String defaultFilter, String unit,
                               List<String> aliases, String version) {

    public MetricDefinition {
        aliases = List.copyOf(aliases);
    }
}
