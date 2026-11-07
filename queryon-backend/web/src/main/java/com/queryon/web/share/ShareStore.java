package com.queryon.web.share;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.web.dto.QueryRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分享链接台账（D3-07）：保存查询定义，按提问者身份隔离。
 * 访问链接仍需有效身份头且必须与创建者一致，用创建者的行列级权限执行，
 * 因此分享不会扩大数据可见范围（G4）。
 */
@Component
public class ShareStore {

    public record SharedQuery(String token, String name, QueryRequest query, String actorId) {
    }

    private final Map<String, SharedQuery> shares = new ConcurrentHashMap<>();

    public SharedQuery save(String name, QueryRequest query, String actorId) {
        String token = "sh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String label = (name == null || name.isBlank()) ? "未命名分享" : name.trim();
        SharedQuery saved = new SharedQuery(token, label, query, actorId);
        shares.put(token, saved);
        return saved;
    }

    public List<SharedQuery> list(String actorId) {
        return shares.values().stream()
                .filter(item -> item.actorId().equals(actorId))
                .sorted(Comparator.comparing(SharedQuery::token))
                .toList();
    }

    public SharedQuery get(String token, String actorId) {
        SharedQuery found = shares.get(token);
        if (found == null || !found.actorId().equals(actorId)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "分享",
                    "分享链接不存在或当前身份无权访问", "分享链接按创建者身份隔离");
        }
        return found;
    }

    public void revoke(String token, String actorId) {
        get(token, actorId);
        shares.remove(token);
    }
}
