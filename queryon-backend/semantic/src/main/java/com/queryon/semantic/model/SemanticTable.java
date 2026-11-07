package com.queryon.semantic.model;

import java.util.List;
import java.util.Optional;

/** 语义层表：物理表 + 业务描述 + 字段清单（05 第 8.1 节）。 */
public record SemanticTable(String name, String businessName, String description,
                            List<SemanticColumn> columns) {

    public SemanticTable {
        columns = List.copyOf(columns);
    }

    public Optional<SemanticColumn> column(String columnName) {
        return columns.stream().filter(column -> column.name().equals(columnName)).findFirst();
    }

    public boolean hasColumn(String columnName) {
        return column(columnName).isPresent();
    }

    public List<String> columnNames() {
        return columns.stream().map(SemanticColumn::name).toList();
    }
}
