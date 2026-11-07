package com.queryon.query.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置示例数据集的语义宽表视图。全部为假数据，由 {@link SampleDataGenerator} 在内存中生成，
 * 不写入任何数据文件、SQL 种子或本地覆盖配置。
 */
public final class SampleDataset {

    private final Map<String, List<Map<String, Object>>> tables;

    public SampleDataset(Map<String, List<Map<String, Object>>> tables) {
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        tables.forEach((name, rows) -> copy.put(name, List.copyOf(rows)));
        this.tables = Map.copyOf(copy);
    }

    public List<Map<String, Object>> rows(String table) {
        return tables.getOrDefault(table, List.of());
    }

    public long rowCount(String table) {
        return rows(table).size();
    }

    public Set<String> tableNames() {
        return tables.keySet();
    }
}
