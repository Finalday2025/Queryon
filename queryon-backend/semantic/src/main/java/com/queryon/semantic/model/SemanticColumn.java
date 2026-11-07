package com.queryon.semantic.model;

import java.util.List;

/**
 * 语义层字段。中文业务名、说明与同义词来自 05 第 8.2 节，供 Schema Linking 检索。
 *
 * @param name        物理字段名
 * @param businessName 中文业务名
 * @param description 业务说明
 * @param synonyms    同义词／业务黑话
 * @param sensitive   是否敏感列（列级权限依据）
 */
public record SemanticColumn(String name, String businessName, String description,
                             List<String> synonyms, boolean sensitive) {

    public SemanticColumn {
        synonyms = List.copyOf(synonyms);
    }

    public static SemanticColumn normal(String name, String businessName, String description, String... synonyms) {
        return new SemanticColumn(name, businessName, description, List.of(synonyms), false);
    }

    public static SemanticColumn sensitive(String name, String businessName, String description, String... synonyms) {
        return new SemanticColumn(name, businessName, description, List.of(synonyms), true);
    }
}
