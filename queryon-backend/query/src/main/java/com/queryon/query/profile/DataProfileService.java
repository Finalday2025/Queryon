package com.queryon.query.profile;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticTable;

import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据预览与值画像（D1-06）：采样预览、高频枚举值、空值率、数值分布与时间范围。
 * 只接受语义层注册表内的表／字段，不让调用方直接面对未受管物理列（G1）。
 * 数据来自内存示例数据集，不写入任何数据文件。
 */
public final class DataProfileService {

    public record ColumnProfile(String table, String column, String businessName, int totalRows,
                                double nullRate, List<Map<String, Object>> sample,
                                List<Map<String, Object>> topValues,
                                Map<String, Object> distribution, String timeRange) {
    }

    private final SemanticRegistry registry;
    private final SampleDataset dataset;

    public DataProfileService(SemanticRegistry registry, SampleDataset dataset) {
        this.registry = registry;
        this.dataset = dataset;
    }

    public ColumnProfile profile(String table, String column) {
        SemanticTable semanticTable = registry.table(table);
        if (!registry.hasField(column)) {
            throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "Schema 门",
                    "字段 " + column + " 不在语义模型内",
                    "请从字段目录中选择受管字段（示例宽表 " + semanticTable.businessName() + " 同样受语义层约束）");
        }

        List<Map<String, Object>> rows = dataset.rows(table);
        int total = rows.size();
        long nulls = rows.stream().filter(row -> row.get(column) == null).count();

        Map<Object, Long> frequency = new LinkedHashMap<>();
        DoubleSummaryStatistics numbers = new DoubleSummaryStatistics();
        String minText = null;
        String maxText = null;
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value == null) {
                continue;
            }
            frequency.merge(value, 1L, Long::sum);
            if (value instanceof Number number) {
                numbers.accept(number.doubleValue());
            }
            String text = String.valueOf(value);
            if (minText == null || text.compareTo(minText) < 0) {
                minText = text;
            }
            if (maxText == null || text.compareTo(maxText) > 0) {
                maxText = text;
            }
        }

        List<Map<String, Object>> topValues = frequency.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("value", String.valueOf(entry.getKey()));
                    item.put("count", entry.getValue());
                    return item;
                })
                .toList();

        Map<String, Object> distribution = new LinkedHashMap<>();
        if (numbers.getCount() > 0) {
            distribution.put("min", numbers.getMin());
            distribution.put("max", numbers.getMax());
            distribution.put("avg", numbers.getAverage());
            distribution.put("numericCount", numbers.getCount());
        }

        List<Map<String, Object>> sample = rows.stream().limit(5).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", row.get(column));
            return item;
        }).toList();

        boolean dateColumn = column.contains("date") || column.endsWith("_at");
        String timeRange = dateColumn && minText != null ? minText + " ~ " + maxText : "";

        return new ColumnProfile(table, column, registry.businessNameOf(column), total,
                total == 0 ? 0 : (double) nulls / total, sample, topValues,
                Map.copyOf(distribution), timeRange);
    }
}
