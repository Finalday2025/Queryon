package com.queryon.query.guard;

import com.queryon.common.context.Actor;
import com.queryon.common.context.AskTrace;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.plan.PhysicalQuery;

/** 护栏链路共享上下文；解析门产出的 AST 摘要供后续门复用。 */
public final class GuardContext {

    private final PhysicalQuery physicalQuery;
    private final SemanticQuery semanticQuery;
    private final Actor actor;
    private final AskTrace trace;
    private final SchemaLinkResult schemaLink;
    private final long sourceRowCount;
    private SqlAst sqlAst;

    public GuardContext(PhysicalQuery physicalQuery, SemanticQuery semanticQuery, Actor actor,
                        AskTrace trace, SchemaLinkResult schemaLink, long sourceRowCount) {
        this.physicalQuery = physicalQuery;
        this.semanticQuery = semanticQuery;
        this.actor = actor;
        this.trace = trace;
        this.schemaLink = schemaLink;
        this.sourceRowCount = sourceRowCount;
    }

    public PhysicalQuery physicalQuery() {
        return physicalQuery;
    }

    public SemanticQuery semanticQuery() {
        return semanticQuery;
    }

    public Actor actor() {
        return actor;
    }

    public AskTrace trace() {
        return trace;
    }

    public SchemaLinkResult schemaLink() {
        return schemaLink;
    }

    public long sourceRowCount() {
        return sourceRowCount;
    }

    public SqlAst sqlAst() {
        return sqlAst;
    }

    public void setSqlAst(SqlAst sqlAst) {
        this.sqlAst = sqlAst;
    }
}
