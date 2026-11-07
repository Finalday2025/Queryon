package com.queryon.query.guard;

import java.util.List;

/**
 * 由 JSqlParser 解析出的 AST 摘要。语句类型已在解析阶段按 AST 节点类型判定，
 * 不使用正则判断。
 *
 * @param sql             SQL 文本快照
 * @param tables          AST 中提取的物理表名
 * @param functions       SELECT 表达式中的函数名
 * @param joinCount       join 数量
 * @param selectItemCount 投影列数量
 */
public record SqlAst(String sql, List<String> tables, List<String> functions,
                     int joinCount, int selectItemCount) {
}
