package com.queryon.semantic.model;

/** 批准的星型连接路径；禁止模型自由拼 join 或跨事实表关联（G1、D6-02）。 */
public record JoinPath(String fromTable, String toTable, String fromKey, String toKey) {

    public String display() {
        return fromTable + "." + fromKey + " -> " + toTable + "." + toKey;
    }
}
