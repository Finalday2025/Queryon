package com.queryon.query.executor;

import com.queryon.common.result.ResultSet;
import com.queryon.semantic.plan.ExecutionPlan;

/**
 * 统一查询执行入口；所有入口消费同一份语义层翻译出的执行计划。
 * 实现可替换（内存示例数据集 / PostgreSQL），但都不得绕过语义层执行计划。
 */
public interface QueryExecutor {

    ResultSet execute(ExecutionPlan plan);

    /** 执行门用于预估扫描量的基表行数；内存实现用内置数据集，JDBC 实现查库。 */
    default long estimateRowCount(String table) {
        return 0L;
    }

    /**
     * 该执行器实际执行的 SQL，供结论溯源与审计展示；内存执行器没有物理 SQL，返回 {@code null}，
     * 由调用方回退到语义计划快照。渲染必须是同一计划到 SQL 的确定性映射，不得改写口径。
     */
    default String renderedSql(ExecutionPlan plan) {
        return null;
    }
}
