package com.queryon.semantic.model;

import java.util.List;

/** 受管维度：维度编码 → 物理表与字段，只允许语义层批准的星型路径。 */
public record DimensionDefinition(String code, String businessName, String table,
                                  String column, List<String> aliases) {

    public DimensionDefinition {
        aliases = List.copyOf(aliases);
    }
}
