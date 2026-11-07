package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.query.QueryService;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dataset.LogicalDatasetStore;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 逻辑数据集（`D1-05`）：裁剪受管指标／维度／关联路径形成分析单元，
 * 支持共享；查询统一走受治理入口，权限随提问者而非数据集创建者。
 */
@RestController
@RequestMapping("/api/logical-datasets")
public class LogicalDatasetController {

    private final LogicalDatasetStore store;
    private final QueryService queryService;
    private final IdentityResolver identityResolver;

    public LogicalDatasetController(LogicalDatasetStore store, QueryService queryService,
                                    IdentityResolver identityResolver) {
        this.store = store;
        this.queryService = queryService;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<LogicalDatasetStore.View> list(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        return store.list(identityResolver.resolve(userId));
    }

    @GetMapping("/{id}")
    public LogicalDatasetStore.View detail(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        return store.accessible(id, identityResolver.resolve(userId));
    }

    @PostMapping
    public LogicalDatasetStore.View create(
            @RequestBody LogicalDatasetStore.Input input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        return store.create(input, identityResolver.resolve(userId));
    }

    @PutMapping("/{id}")
    public LogicalDatasetStore.View update(
            @PathVariable String id,
            @RequestBody LogicalDatasetStore.Input input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        return store.update(id, input, identityResolver.resolve(userId));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        store.delete(id, actor);
        return Map.of("status", "已删除", "id", id);
    }

    /**
     * 按数据集取数：指标缺省时用数据集内全部指标；默认过滤先注入、请求过滤优先；
     * 执行身份为当前提问者，行／列级权限与审计照常生效。
     */
    @PostMapping("/{id}/query")
    public QueryResponse query(
            @PathVariable String id,
            @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        LogicalDatasetStore.View dataset = store.accessible(id, actor);
        SemanticQuery query = request.toSemanticQuery(LocalDate.now());
        if (query.metrics().isEmpty()) {
            query = query.withMetrics(dataset.metrics());
        }
        store.validateScope(dataset, query);
        for (Map.Entry<String, String> entry : dataset.defaultFilters().entrySet()) {
            if (!query.filters().containsKey(entry.getKey())) {
                query = query.withFilter(entry.getKey(), entry.getValue());
            }
        }
        return QueryResponse.from(queryService.execute(query, actor));
    }
}
