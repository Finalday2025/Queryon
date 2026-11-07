package com.queryon.evaluation;

import java.util.List;

/** 单条样本的回归结果，含语义指纹与失败原因。 */
public record CaseResult(int id, String question, String scene, String role, String dialect,
                         String status, String fingerprint, boolean passed, List<String> failures) {

    public CaseResult {
        failures = List.copyOf(failures);
    }
}
