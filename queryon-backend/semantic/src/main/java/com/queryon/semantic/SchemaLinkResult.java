package com.queryon.semantic;

import java.util.List;

/**
 * Schema Linking 候选集：命中项与得分可查看，错误召回可定位（D4-02）。
 *
 * @param metricCodes         命中的受管指标编码
 * @param dimensionCodes      命中的受管维度编码
 * @param fieldNames          命中的物理字段
 * @param matchedTerms        命中的中文业务名／同义词
 * @param ambiguousTerms      命中但无唯一认证口径的歧义词
 * @param privateCaliberTerms 命中私有口径改写请求的词
 */
public record SchemaLinkResult(List<String> metricCodes, List<String> dimensionCodes,
                               List<String> fieldNames, List<String> matchedTerms,
                               List<String> ambiguousTerms, List<String> privateCaliberTerms) {

    public SchemaLinkResult {
        metricCodes = List.copyOf(metricCodes);
        dimensionCodes = List.copyOf(dimensionCodes);
        fieldNames = List.copyOf(fieldNames);
        matchedTerms = List.copyOf(matchedTerms);
        ambiguousTerms = List.copyOf(ambiguousTerms);
        privateCaliberTerms = List.copyOf(privateCaliberTerms);
    }

    public boolean hasAmbiguity() {
        return !ambiguousTerms.isEmpty();
    }
}
