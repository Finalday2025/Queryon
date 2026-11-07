package com.queryon.query.guard;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.JoinPath;
import com.queryon.semantic.plan.PlanFilter;

/** Schema 门：表／字段／join key 真实存在，且关联路径为语义层批准路径。 */
public final class SchemaGate implements Gate {

    private final SemanticRegistry registry;

    public SchemaGate(SemanticRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "Schema 门";
    }

    @Override
    public void check(GuardContext context) {
        SqlAst ast = context.sqlAst();
        for (String table : ast.tables()) {
            registry.table(table);
            if (!context.actor().canRead(table)) {
                throw new QueryonException(ErrorCode.PERMISSION_DENIED, name(),
                        "身份无权读取数据集 " + table, "请更换有权限的身份或申请数据集授权");
            }
        }
        for (String dimensionCode : context.semanticQuery().dimensions()) {
            DimensionDefinition dimension = registry.dimension(dimensionCode);
            if (dimension == null || !registry.table(dimension.table()).hasColumn(dimension.column())) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, name(),
                        "维度 " + dimensionCode + " 对应的字段不存在", "请从维度目录中选择，系统不会编造字段");
            }
        }
        for (PlanFilter filter : context.physicalQuery().plan().filters()) {
            if (!registry.hasField(filter.column())) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, name(),
                        "过滤字段 " + filter.column() + " 不存在", "请检查字段名或改用受管维度");
            }
        }
        for (JoinPath path : context.physicalQuery().plan().joinPaths()) {
            if (!registry.isApprovedJoin(path.fromTable(), path.toTable())) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, name(),
                        "关联路径 " + path.display() + " 未获批准", "只允许语义层批准的星型连接");
            }
        }
    }
}
