package com.queryon.web.metadata;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.profile.DataProfileService;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticTable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dev 模式下的数据库值画像（D1-06）：列名经过语义层白名单 + 标识符格式校验后才拼接，
 * 只执行只读聚合查询；不承担任何业务取数，业务数据仍由受治理查询入口统一出口。
 */
@Component
@ConditionalOnProperty(name = "queryon.data.source", havingValue = "jdbc")
public class JdbcProfileService {

    private static final List<String> TABLES = List.of(
            "dim_region", "dim_customer", "dim_product", "fact_order", "fact_order_item");

    private final JdbcTemplate jdbcTemplate;
    private final SemanticRegistry registry;

    public JdbcProfileService(JdbcTemplate jdbcTemplate, SemanticRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    public DataProfileService.ColumnProfile profile(String table, String column) {
        SemanticTable semanticTable = registry.table(table);
        if (!TABLES.contains(table) || !registry.hasField(column)
                || !column.matches("[a-z_][a-z0-9_]*")) {
            throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "Schema 门",
                    "表或字段不在语义层白名单内", "请从字段目录中选择受管字段");
        }
        String col = column;

        Map<String, Object> base = jdbcTemplate.queryForMap(
                "SELECT count(*) AS total, "
                        + "count(*) FILTER (WHERE " + col + " IS NULL) AS nulls FROM " + table);

        boolean numeric = isNumeric(table, col);
        Map<String, Object> distribution = new LinkedHashMap<>();
        if (numeric) {
            Map<String, Object> stats = jdbcTemplate.queryForMap(
                    "SELECT min(" + col + ") AS min_value, max(" + col + ") AS max_value, "
                            + "avg(" + col + ") AS avg_value FROM " + table);
            distribution.put("min", stats.get("min_value"));
            distribution.put("max", stats.get("max_value"));
            distribution.put("avg", stats.get("avg_value"));
        }

        List<Map<String, Object>> topValues = jdbcTemplate.queryForList(
                "SELECT " + col + "::text AS value, count(*) AS count FROM " + table
                        + " WHERE " + col + " IS NOT NULL GROUP BY " + col
                        + " ORDER BY count(*) DESC LIMIT 5");
        List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                "SELECT " + col + "::text AS value FROM " + table + " LIMIT 5");

        String timeRange = "";
        if (column.contains("date") || column.endsWith("_at")) {
            Map<String, Object> range = jdbcTemplate.queryForMap(
                    "SELECT min(" + col + "::text) AS min_value, max(" + col + "::text) AS max_value FROM " + table);
            timeRange = String.valueOf(range.get("min_value")) + " ~ " + String.valueOf(range.get("max_value"));
        }

        int total = ((Number) base.get("total")).intValue();
        long nulls = ((Number) base.get("nulls")).longValue();
        return new DataProfileService.ColumnProfile(table, column,
                registry.businessNameOf(column), total, total == 0 ? 0 : (double) nulls / total,
                new ArrayList<>(sample), new ArrayList<>(topValues),
                distribution, timeRange);
    }

    private boolean isNumeric(String table, String column) {
        List<String> types = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
        if (types.isEmpty()) {
            return false;
        }
        String type = types.getFirst();
        return type.contains("int") || type.contains("numeric") || type.contains("decimal")
                || type.contains("real") || type.contains("double");
    }
}
