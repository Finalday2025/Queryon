package com.queryon.query.guard;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;

import java.util.Set;

/** 解析门：AST 解析、只读语句白名单、多语句拦截、非白名单函数拦截。 */
public final class ParseGate implements Gate {

    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "SUM", "COUNT", "AVG", "MIN", "MAX", "ROUND", "COALESCE", "NULLIF");

    private final SqlAstInspector inspector = new SqlAstInspector();

    @Override
    public String name() {
        return "解析门";
    }

    @Override
    public void check(GuardContext context) {
        SqlAst ast = inspector.inspect(context.physicalQuery().sql());
        for (String function : ast.functions()) {
            if (!ALLOWED_FUNCTIONS.contains(function)) {
                throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, name(),
                        "函数 " + function + " 不在只读白名单内", "请改用受管指标或白名单内的聚合函数");
            }
        }
        context.setSqlAst(ast);
    }
}
