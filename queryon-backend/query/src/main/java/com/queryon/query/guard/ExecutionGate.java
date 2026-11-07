package com.queryon.query.guard;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.plan.ExecutionPlan;

/** 执行门：只读白名单复核、join 规模、投影规模、行数与扫描量上限。 */
public final class ExecutionGate implements Gate {

    public static final int MAX_ROW_LIMIT = 1000;
    public static final int MAX_JOINS = 4;
    public static final int MAX_SELECT_ITEMS = 10;
    public static final long MAX_SCAN_ROWS = 100_000L;

    @Override
    public String name() {
        return "执行门";
    }

    @Override
    public void check(GuardContext context) {
        ExecutionPlan plan = context.physicalQuery().plan();
        SqlAst ast = context.sqlAst();
        if (plan.limit() <= 0 || plan.limit() > MAX_ROW_LIMIT) {
            throw new QueryonException(ErrorCode.EXECUTION_GATE_REJECT, name(),
                    "行数上限 " + plan.limit() + " 超出允许范围", "请把行数上限调整到 1～" + MAX_ROW_LIMIT);
        }
        if (ast.joinCount() > MAX_JOINS) {
            throw new QueryonException(ErrorCode.EXECUTION_GATE_REJECT, name(),
                    "join 数量 " + ast.joinCount() + " 超出上限", "请减少维度，避免大范围关联扫描");
        }
        if (ast.selectItemCount() > MAX_SELECT_ITEMS) {
            throw new QueryonException(ErrorCode.EXECUTION_GATE_REJECT, name(),
                    "投影列数量超出上限", "请减少指标与维度数量");
        }
        if (context.sourceRowCount() > MAX_SCAN_ROWS) {
            throw new QueryonException(ErrorCode.EXECUTION_GATE_REJECT, name(),
                    "预估扫描量 " + context.sourceRowCount() + " 行超出上限", "请补充时间窗或维度过滤后重试");
        }
        context.trace().record("护栏", name() + "：只读白名单复核通过，预估扫描 " + context.sourceRowCount() + " 行");
    }
}
