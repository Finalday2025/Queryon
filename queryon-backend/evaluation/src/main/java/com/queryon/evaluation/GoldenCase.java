package com.queryon.evaluation;

import java.util.List;

/**
 * 一条 golden eval set 样本。字段对齐 03 第 7.1 节结构，并补功能点与护栏映射。
 *
 * @param id                序号（纯数字，不引入新编号前缀）
 * @param question          问句
 * @param semanticAssertion 期望语义查询／结果断言（可校验的语义约束）
 * @param dialect           数据源方言标签，当前统一 PG
 * @param role              身份与权限上下文
 * @param scene             场景标签
 * @param versionSource     版本与来源
 * @param mappings          对应功能点与护栏
 * @param contextQuestions  多轮样本的前置问句，按顺序回放
 * @param expectations      机器可校验的断言
 */
public record GoldenCase(int id, String question, String semanticAssertion, String dialect,
                         String role, String scene, String versionSource,
                         List<String> mappings, List<String> contextQuestions,
                         List<Expectation> expectations) {

    public GoldenCase {
        mappings = List.copyOf(mappings);
        contextQuestions = List.copyOf(contextQuestions);
        expectations = List.copyOf(expectations);
    }
}
