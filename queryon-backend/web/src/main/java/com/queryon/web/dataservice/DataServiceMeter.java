package com.queryon.web.dataservice;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据服务计量（`D8-09` 的「可计量」部分）：记录调用主体、结果行数、耗时与结果状态，
 * 不记录请求体、不记录业务明细、不记录服务密钥；配额与限流按 `05` 第 4 节属企业版 `D7-07`。
 */
@Component
public class DataServiceMeter {

    /**
     * @param outcome 成功／拒绝（护栏拦截或鉴权失败不落行数）
     */
    public record Call(String requestId, String subject, String client, String outcome,
                       int rows, long elapsedMs, String at) {
    }

    public record Usage(long totalCalls, long succeeded, long rejected,
                        Map<String, Long> bySubject, List<Call> recent) {
    }

    private static final int MAX_RECENT = 50;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, Long> bySubject = new ConcurrentHashMap<>();
    private final List<Call> recent = new CopyOnWriteArrayList<>();

    public Call record(String subject, String client, String outcome, int rows, long elapsedMs) {
        String requestId = "svc-" + sequence.incrementAndGet();
        String actor = subject == null || subject.isBlank() ? "unknown" : subject;
        if ("成功".equals(outcome)) {
            succeeded.incrementAndGet();
            bySubject.merge(actor, 1L, Long::sum);
        } else {
            rejected.incrementAndGet();
        }
        Call call = new Call(requestId, actor, client == null || client.isBlank() ? "未标注" : client,
                outcome, rows, elapsedMs, LocalDateTime.now().format(TIME_FORMAT));
        recent.add(call);
        while (recent.size() > MAX_RECENT) {
            recent.remove(0);
        }
        return call;
    }

    public Usage usage() {
        List<Call> ordered = new ArrayList<>(recent);
        java.util.Collections.reverse(ordered);
        return new Usage(sequence.get(), succeeded.get(), rejected.get(),
                Map.copyOf(bySubject), List.copyOf(ordered));
    }
}
