package com.queryon.query.guard;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.TimeWindow;

/** 语义门：命中受管指标、时间口径与单位合规，拒绝私有口径与未认证别名。 */
public final class SemanticGate implements Gate {

    private static final int MAX_METRICS_PER_QUERY = 2;

    private final SemanticRegistry registry;

    public SemanticGate(SemanticRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "语义门";
    }

    @Override
    public void check(GuardContext context) {
        for (String code : context.semanticQuery().metrics()) {
            registry.requireMetric(code);
        }
        if (context.semanticQuery().metrics().size() > MAX_METRICS_PER_QUERY) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, name(),
                    "单次查询指标数超过上限 " + MAX_METRICS_PER_QUERY, "请拆分问句，或分别查询认证指标");
        }
        TimeWindow window = context.semanticQuery().timeWindow();
        if (!SemanticRegistry.COL_ORDER_DATE.equals(window.basisColumn())) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, name(),
                    "时间窗未按 order_date 归属", "认证口径要求按 order_date 归属，请改用受管时间口径");
        }
        SchemaLinkResult link = context.schemaLink();
        if (!link.privateCaliberTerms().isEmpty()) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, name(),
                    "请求使用私有口径：" + String.join("、", link.privateCaliberTerms()),
                    "语义层只提供唯一认证口径，可在口径面板查看定义，不私自改写公式");
        }
    }
}
