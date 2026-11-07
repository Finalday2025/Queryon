package com.queryon.semantic.plan;

/**
 * 物理查询快照。SQL 文本用于解析门 AST 校验、结论溯源与口径展示；
 * 执行计划用于统一执行入口，二者由同一个翻译器一次生成，不会口径分裂。
 */
public record PhysicalQuery(String sql, ExecutionPlan plan) {
}
