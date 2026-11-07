package com.queryon.common.context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次问数的全链路追踪。只记录阶段、命中规则与拦截原因，不记录原始问句、
 * prompt 与 completion 内容。
 */
public final class AskTrace {

    /** @param stage  阶段名（提问／上下文补全／语义检索／生成／四道门／执行／输出／反馈） */
    public record Step(String stage, String detail) {
    }

    private final String traceId = UUID.randomUUID().toString();
    private final List<Step> steps = new ArrayList<>();

    public String traceId() {
        return traceId;
    }

    public void record(String stage, String detail) {
        steps.add(new Step(stage, detail));
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public List<String> gates() {
        return steps.stream()
                .filter(step -> step.stage().startsWith("护栏"))
                .map(Step::detail)
                .toList();
    }
}
