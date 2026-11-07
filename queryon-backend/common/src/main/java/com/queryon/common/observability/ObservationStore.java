package com.queryon.common.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存可观测存储（D8-06）：所有取数入口都归一到受治理查询层，故在此统一记录
 * 调用次数、耗时、慢查询、错误率与护栏拦截统计，并保留单次链路供结论溯源（D6-06）。
 * 不引入独立监控组件、不记录 prompt／completion 内容与任何凭据。
 */
public final class ObservationStore {

    public static final long SLOW_QUERY_MS = 500;
    private static final int MAX_EVENTS = 500;

    public record Event(String traceId, String userId, String role, String entry,
                        long durationMs, boolean success, String errorCode,
                        int rows, List<String> stages) {
        public Event {
            stages = List.copyOf(stages);
        }
    }

    public record Snapshot(long total, long succeeded, long rejected, long slowQueries,
                           double avgDurationMs, Map<String, Long> byEntry,
                           Map<String, Long> byError, List<Event> recent) {
    }

    private final long slowThresholdMs;
    private final List<Event> events = new CopyOnWriteArrayList<>();

    public ObservationStore() {
        this(SLOW_QUERY_MS);
    }

    /** 阈值可注入，便于测试慢查询统计而不依赖真实耗时。 */
    public ObservationStore(long slowThresholdMs) {
        this.slowThresholdMs = slowThresholdMs;
    }

    public void record(Event event) {
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    public Snapshot snapshot() {
        long total = events.size();
        long succeeded = events.stream().filter(Event::success).count();
        long rejected = total - succeeded;
        long slow = events.stream().filter(e -> e.durationMs() >= slowThresholdMs).count();
        double avg = events.stream().mapToLong(Event::durationMs).average().orElse(0);

        Map<String, Long> byEntry = new LinkedHashMap<>();
        Map<String, Long> byError = new LinkedHashMap<>();
        for (Event event : events) {
            byEntry.merge(event.entry(), 1L, Long::sum);
            if (event.errorCode() != null) {
                byError.merge(event.errorCode(), 1L, Long::sum);
            }
        }
        List<Event> recent = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && recent.size() < 10; i--) {
            recent.add(events.get(i));
        }
        return new Snapshot(total, succeeded, rejected, slow, avg,
                Map.copyOf(byEntry), Map.copyOf(byError), List.copyOf(recent));
    }

    public Optional<Event> trace(String traceId) {
        return events.stream().filter(event -> event.traceId().equals(traceId)).findFirst();
    }
}
