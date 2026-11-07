package com.queryon.semantic;

/**
 * 语义查询翻译的方言策略。只影响 SQL 快照中的标识符引用，不改变执行计划与指标口径；
 * 所有入口仍共用同一个 {@link SemanticTranslator}，按方言分组回归时口径保持一致。
 *
 * <p>PG 保持既有快照（不加引号），MYSQL 用反引号引用标识符，作为第二组可回归方言。
 * 这里做的是轻量翻译策略，不引入 Apache Calcite 等翻译引擎（按 05 第 4 节归企业版）。
 */
public enum SqlDialect {

    PG {
        @Override
        public String quote(String identifier) {
            return identifier;
        }
    },

    MYSQL {
        @Override
        public String quote(String identifier) {
            return "`" + identifier + "`";
        }
    };

    public abstract String quote(String identifier);
}
