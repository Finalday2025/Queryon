package com.queryon.query.guard;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析门底层：把 SQL 解析成 AST 后提取语句类型、表名、函数与 join 规模。
 * 只接受单条 PlainSelect；DDL／DML／多语句／集合运算一律拒绝。
 */
public final class SqlAstInspector {

    public SqlAst inspect(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1) {
                throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, "解析门",
                        "检测到多语句", "一次只允许提交一条只读查询");
            }
            Statement statement = statements.getFirst();
            if (!(statement instanceof PlainSelect select)) {
                throw new QueryonException(ErrorCode.WRITE_OPERATION_REJECT, "解析门",
                        "语句类型不是只读 SELECT", "所有 AI 输出限定为查询与展示，写操作一律拒绝");
            }

            List<String> tables = new TablesNamesFinder<Void>().getTableList(statement);
            List<String> functions = new ArrayList<>();
            ExpressionVisitorAdapter<Void> visitor = new ExpressionVisitorAdapter<>() {
                @Override
                public <S> Void visit(Function function, S context) {
                    functions.add(function.getName().toUpperCase(Locale.ROOT));
                    return null;
                }
            };
            for (SelectItem<?> item : select.getSelectItems()) {
                Expression expression = item.getExpression();
                if (expression != null) {
                    expression.accept(visitor, null);
                }
            }
            int joinCount = select.getJoins() == null ? 0 : select.getJoins().size();
            return new SqlAst(sql, tables, functions, joinCount, select.getSelectItems().size());
        } catch (JSQLParserException e) {
            throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, "解析门",
                    "SQL 无法解析成 AST", "请检查语句是否完整，或改用语义层的受管指标与维度");
        }
    }
}
