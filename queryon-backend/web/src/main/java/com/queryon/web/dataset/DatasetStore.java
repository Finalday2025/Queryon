package com.queryon.web.dataset;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.web.dto.QueryResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话内数据集复用：一次取数的结果按提问者身份隔离缓存，报表与交叉表可直接复用，无需重新查询。
 * 结果由服务端执行统一查询入口生成（四道护栏与权限注入均已生效），不接受客户端提交的 ResultSet，
 * 避免绕过治理横切；读取时校验身份，跨身份一律拒绝且不暴露数据集是否存在。
 */
@Component
public class DatasetStore {

    public record Summary(String id, String name, int rows, List<String> columns) {
    }

    private record Saved(String id, String name, String actorId, QueryResponse response) {
    }

    private final Map<String, Saved> datasets = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Summary save(String name, String actorId, QueryResponse response) {
        String id = "ds-" + sequence.incrementAndGet();
        String label = (name == null || name.isBlank()) ? "未命名数据集" : name.trim();
        Saved saved = new Saved(id, label, actorId, response);
        datasets.put(id, saved);
        return summary(saved);
    }

    public List<Summary> list(String actorId) {
        return datasets.values().stream()
                .filter(saved -> saved.actorId().equals(actorId))
                .sorted(Comparator.comparing(Saved::id))
                .map(this::summary)
                .toList();
    }

    public QueryResponse get(String id, String actorId) {
        Saved saved = datasets.get(id);
        if (saved == null || !saved.actorId().equals(actorId)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "数据集复用",
                    "数据集不存在或当前身份无权读取", "数据集按提问者身份隔离，请切换回创建时的身份");
        }
        return saved.response();
    }

    private Summary summary(Saved saved) {
        return new Summary(saved.id(), saved.name(), saved.response().resultSet().rows().size(),
                saved.response().resultSet().columns());
    }
}
