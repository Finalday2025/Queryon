package com.queryon.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 反馈回流（D4-10、D6-09）：人工纠正样本入库后可直接进入下一次回归，
 * 只在样本表尾部增行，不改写既有 24 条样本的语义与断言。
 */
public final class FeedbackStore {

    private static final String SOURCE = "v0.10／反馈回流";

    private final CopyOnWriteArrayList<FeedbackEntry> entries = new CopyOnWriteArrayList<>();

    public void record(String question, String correctedMetric, String note) {
        entries.add(new FeedbackEntry(question, correctedMetric, note, SOURCE));
    }

    public List<FeedbackEntry> entries() {
        return List.copyOf(entries);
    }

    public List<GoldenCase> asCases() {
        int base = GoldenSet.cases().size();
        List<GoldenCase> cases = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            FeedbackEntry entry = entries.get(i);
            cases.add(new GoldenCase(base + i + 1, entry.question(),
                    "纠正后须命中受管指标 " + entry.correctedMetric(),
                    GoldenSet.DIALECT_PG, "admin_bi", "反馈回流", SOURCE,
                    List.of("D4-10", "D6-09", "G6"), List.of(),
                    List.of(Expectation.of(Expectation.Type.ANSWERED, "纠正样本可回归"),
                            Expectation.of(Expectation.Type.METRIC, "命中纠正后的认证指标",
                                    entry.correctedMetric()))));
        }
        return cases;
    }
}
