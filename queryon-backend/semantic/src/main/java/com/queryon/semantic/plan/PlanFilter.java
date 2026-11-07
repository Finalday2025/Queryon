package com.queryon.semantic.plan;

import java.util.List;

/** 结构化过滤条件；行列级权限与时间窗都以同样的形式由系统注入。 */
public record PlanFilter(String column, Operator operator, List<String> values) {

    public enum Operator {
        EQ, IN, NOT_EQ, GTE, LT
    }

    public PlanFilter {
        values = List.copyOf(values);
    }

    public static PlanFilter eq(String column, String value) {
        return new PlanFilter(column, Operator.EQ, List.of(value));
    }

    public static PlanFilter in(String column, List<String> values) {
        return new PlanFilter(column, Operator.IN, values);
    }

    public static PlanFilter gte(String column, String value) {
        return new PlanFilter(column, Operator.GTE, List.of(value));
    }

    public static PlanFilter lt(String column, String value) {
        return new PlanFilter(column, Operator.LT, List.of(value));
    }
}
