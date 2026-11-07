package com.queryon.web.dataset;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.web.dto.QueryRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅台账（D3-07 社区版「订阅导出」）：保存一次受治理查询与投递频率标签，按身份隔离。
 * 当前提供「立即执行／留痕」，不做 cron 定时推送——定时调度归属需按 05 第 4 节再确认，
 * 社区版不擅自引入 Quartz 等调度依赖。
 */
@Component
public class SubscriptionStore {

    public record Subscription(String id, String name, String frequency, QueryRequest query,
                               String actorId, int runCount, String lastOutcome) {
    }

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Subscription save(String name, String frequency, QueryRequest query, String actorId) {
        String id = "sub-" + sequence.incrementAndGet();
        String label = (name == null || name.isBlank()) ? "未命名订阅" : name.trim();
        String cadence = (frequency == null || frequency.isBlank()) ? "手动" : frequency.trim();
        Subscription saved = new Subscription(id, label, cadence, query, actorId, 0, "未执行");
        subscriptions.put(id, saved);
        return saved;
    }

    public List<Subscription> list(String actorId) {
        return subscriptions.values().stream()
                .filter(item -> item.actorId().equals(actorId))
                .sorted(Comparator.comparing(Subscription::id))
                .toList();
    }

    public Subscription get(String id, String actorId) {
        Subscription found = subscriptions.get(id);
        if (found == null || !found.actorId().equals(actorId)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "订阅",
                    "订阅不存在或当前身份无权访问", "订阅按提问者身份隔离，请切换回创建时的身份");
        }
        return found;
    }

    public Subscription markRun(String id, String actorId, String outcome) {
        Subscription current = get(id, actorId);
        Subscription updated = new Subscription(current.id(), current.name(), current.frequency(),
                current.query(), current.actorId(), current.runCount() + 1, outcome);
        subscriptions.put(id, updated);
        return updated;
    }
}
