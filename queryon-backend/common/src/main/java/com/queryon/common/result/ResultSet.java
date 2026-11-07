package com.queryon.common.result;

import java.util.List;
import java.util.Map;

/**
 * 统一结果集规范，供问数、SQL 编辑器、仪表板与开放 API 共用。
 *
 * @param columns   列名（顺序稳定）
 * @param rows      结果行
 * @param totalRows 执行门截断前的行数
 * @param truncated 是否触发行数上限
 */
public record ResultSet(List<String> columns, List<Map<String, Object>> rows,
                        int totalRows, boolean truncated) {

    public ResultSet {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public static ResultSet empty() {
        return new ResultSet(List.of(), List.of(), 0, false);
    }
}
