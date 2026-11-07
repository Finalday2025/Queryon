package com.queryon.bootstrap.jdbc;

import com.queryon.common.result.ResultSet;
import com.queryon.query.executor.QueryExecutor;
import com.queryon.semantic.plan.ExecutionPlan;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 基于 PostgreSQL 的执行器：只消费语义层执行计划，结果与内存执行器保持同一 ResultSet 规范。 */
public final class JdbcQueryExecutor implements QueryExecutor {

    private static final Set<String> TABLES =
            Set.of("fact_order", "fact_order_item", "dim_region", "dim_customer", "dim_product");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcSqlRenderer renderer;

    public JdbcQueryExecutor(JdbcTemplate jdbcTemplate, JdbcSqlRenderer renderer) {
        this.jdbcTemplate = jdbcTemplate;
        this.renderer = renderer;
    }

    @Override
    public ResultSet execute(ExecutionPlan plan) {
        List<String> columns = new ArrayList<>(plan.dimensionCodes());
        columns.addAll(plan.metricCodes());
        String sql = renderer.render(plan);
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : columns) {
                Object value = rs.getObject(column);
                if (value instanceof Number number
                        && !(value instanceof Double || value instanceof Float
                        || value instanceof Integer || value instanceof Long)) {
                    value = number.doubleValue(); // BigDecimal 归一为与内存执行器一致的数值类型
                }
                row.put(column, value);
            }
            return row;
        });
        return new ResultSet(columns, rows, rows.size(), rows.size() >= plan.limit());
    }

    @Override
    public long estimateRowCount(String table) {
        if (!TABLES.contains(table)) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    /** 与 {@link #execute(ExecutionPlan)} 消费同一份计划、同一渲染器，保证展示与实际执行一致。 */
    @Override
    public String renderedSql(ExecutionPlan plan) {
        return renderer.render(plan);
    }
}
