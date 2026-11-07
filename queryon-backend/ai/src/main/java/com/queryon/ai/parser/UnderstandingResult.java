package com.queryon.ai.parser;

import com.queryon.common.error.StructuredError;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.model.SemanticQuery;

import java.util.List;

/** 混合解析结果：语义查询、澄清、口径说明或结构化拒绝。 */
public record UnderstandingResult(Kind kind, SemanticQuery query, SchemaLinkResult link,
                                  String clarification, StructuredError error,
                                  List<String> definitionNotes) {

    public enum Kind {
        QUERY, CLARIFICATION, DEFINITION, REJECTED
    }

    public UnderstandingResult {
        definitionNotes = List.copyOf(definitionNotes);
    }

    public static UnderstandingResult query(SemanticQuery query, SchemaLinkResult link) {
        return new UnderstandingResult(Kind.QUERY, query, link, null, null, List.of());
    }

    public static UnderstandingResult clarification(String question, SchemaLinkResult link) {
        return new UnderstandingResult(Kind.CLARIFICATION, SemanticQuery.empty(), link,
                question, null, List.of());
    }

    public static UnderstandingResult definition(SchemaLinkResult link, List<String> notes) {
        return new UnderstandingResult(Kind.DEFINITION, SemanticQuery.empty(), link,
                null, null, notes);
    }

    public static UnderstandingResult rejected(StructuredError error, SchemaLinkResult link) {
        return new UnderstandingResult(Kind.REJECTED, SemanticQuery.empty(), link,
                null, error, List.of());
    }
}
