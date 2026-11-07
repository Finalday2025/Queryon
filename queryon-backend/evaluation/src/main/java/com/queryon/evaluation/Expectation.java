package com.queryon.evaluation;

import java.util.List;

/**
 * 可校验的语义约束断言。只写语义约束，不写未经核实的数值 KPI；
 * 具体数值断言在假数据生成并与维护者核对后再固化。
 */
public record Expectation(Type type, List<String> values, String description) {

    public enum Type {
        ANSWERED, CLARIFICATION, DEFINITION, REJECTED,
        METRIC, DIMENSION, TIME_WINDOW, ROW_FILTER, ROW_SCOPE, TOP_N,
        ERROR_GATE, DEFINITION_TEXT
    }

    public Expectation {
        values = List.copyOf(values);
    }

    public static Expectation of(Type type, String description, String... values) {
        return new Expectation(type, List.of(values), description);
    }
}
