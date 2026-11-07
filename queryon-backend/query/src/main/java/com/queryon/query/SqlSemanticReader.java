package com.queryon.query;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.guard.SqlAstInspector;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 编辑器入口：先由解析门把 SQL 解析成 AST，再从 AST 还原为语义查询，
 * 最后仍走统一查询服务。因此 SQL 编辑器与问数、仪表板共用同一套认证口径。
 */
public final class SqlSemanticReader {

    private final SemanticRegistry registry;
    private final SqlAstInspector inspector = new SqlAstInspector();

    public SqlSemanticReader(SemanticRegistry registry) {
        this.registry = registry;
    }

    public SemanticQuery read(String sql) {
        inspector.inspect(sql);
        PlainSelect select = parse(sql);
        List<String> metrics = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            String alias = item.getAliasName();
            if (alias == null) {
                continue;
            }
            if (registry.hasMetric(alias)) {
                metrics.add(alias);
            } else if (registry.dimension(alias) != null) {
                dimensions.add(alias);
            }
        }
        if (metrics.isEmpty()) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "SQL 未命中任何受管指标", "请使用语义层生成的标准投影别名，例如 net_revenue、order_count");
        }

        Map<String, String> filters = new LinkedHashMap<>();
        LocalDate[] range = new LocalDate[2];
        collect(select.getWhere(), filters, range);
        TimeWindow window = buildWindow(range);

        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(metrics)
                .withDimensions(dimensions)
                .withTimeWindow(window);
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            query = query.withFilter(filter.getKey(), filter.getValue());
        }
        return query;
    }

    private PlainSelect parse(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof PlainSelect select) {
                return select;
            }
            throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, "解析门",
                    "SQL 不是单条只读 SELECT", "请提交语义层生成的标准查询");
        } catch (JSQLParserException e) {
            throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, "解析门",
                    "SQL 无法解析成 AST", "请检查语句是否完整");
        }
    }

    private static void collect(Expression expression, Map<String, String> filters, LocalDate[] range) {
        if (expression == null) {
            return;
        }
        if (expression instanceof AndExpression and) {
            collect(and.getLeftExpression(), filters, range);
            collect(and.getRightExpression(), filters, range);
            return;
        }
        if (expression instanceof ComparisonOperator comparison) {
            Expression left = comparison.getLeftExpression();
            Expression right = comparison.getRightExpression();
            String column = left instanceof Column columnExpression ? columnExpression.getColumnName() : null;
            String value = right instanceof StringValue stringValue ? stringValue.getValue() : null;
            if (column == null || value == null) {
                return;
            }
            if (expression instanceof EqualsTo) {
                filters.put(column, value);
            } else if (expression instanceof GreaterThanEquals
                    && SemanticRegistry.COL_ORDER_DATE.equals(column)) {
                range[0] = LocalDate.parse(value);
            } else if (expression instanceof MinorThan
                    && SemanticRegistry.COL_ORDER_DATE.equals(column)) {
                range[1] = LocalDate.parse(value);
            }
        }
    }

    private static TimeWindow buildWindow(LocalDate[] range) {
        if (range[0] == null || range[1] == null) {
            return TimeWindow.all();
        }
        boolean firstOfMonth = range[0].getDayOfMonth() == 1;
        boolean fullMonth = firstOfMonth && range[1].equals(range[0].plusMonths(1));
        // 只覆盖当月一部分（如本月至今）时不再标成本年至今，避免把两种口径混为一谈。
        boolean monthToDate = firstOfMonth && range[1].isBefore(range[0].plusMonths(1));
        TimeWindow.Type type = fullMonth ? TimeWindow.Type.LAST_MONTH
                : monthToDate ? TimeWindow.Type.MONTH_TO_DATE : TimeWindow.Type.YEAR_TO_DATE;
        String label = switch (type) {
            case LAST_MONTH -> "上一自然月，按 order_date 归属";
            case MONTH_TO_DATE -> "本月至今，按 order_date 归属";
            default -> "本年至今，按 order_date 归属";
        };
        return new TimeWindow(type, range[0], range[1], SemanticRegistry.COL_ORDER_DATE, label);
    }

}
