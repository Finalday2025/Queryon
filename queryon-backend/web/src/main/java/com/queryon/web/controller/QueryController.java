package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dataset.DatasetStore;
import com.queryon.web.dto.DatasetSaveRequest;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 统一查询入口：SQL 编辑器提交 sql，仪表板与开放 API 提交结构化语义查询，
 * 二者归一到同一个 SemanticQuery 后共用同一语义层、四道护栏与权限注入。
 * 数据集端点复用同一执行结果，按提问者身份隔离缓存，不旁路治理横切。
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;
    private final SqlSemanticReader sqlSemanticReader;
    private final IdentityResolver identityResolver;
    private final DatasetStore datasetStore;

    public QueryController(QueryService queryService, SqlSemanticReader sqlSemanticReader,
                           IdentityResolver identityResolver, DatasetStore datasetStore) {
        this.queryService = queryService;
        this.sqlSemanticReader = sqlSemanticReader;
        this.identityResolver = identityResolver;
        this.datasetStore = datasetStore;
    }

    @PostMapping("/queries")
    public QueryResponse execute(@RequestBody QueryRequest request,
                                 @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        SemanticQuery query = request.sql() != null && !request.sql().isBlank()
                ? sqlSemanticReader.read(request.sql())
                : request.toSemanticQuery(LocalDate.now());
        return QueryResponse.from(queryService.execute(query, actor));
    }

    /** 保存一次取数结果为可复用数据集：服务端执行查询后缓存，不接受客户端提交的结果集。 */
    @PostMapping("/datasets")
    public DatasetStore.Summary saveDataset(@RequestBody DatasetSaveRequest request,
                                            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        if (request == null || request.query() == null) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "数据集复用",
                    "缺少查询定义", "请提供 metrics／dimensions 或 sql");
        }
        Actor actor = identityResolver.resolve(userId);
        QueryResponse response = execute(request.query(), userId);
        return datasetStore.save(request.name(), actor.userId(), response);
    }

    /** 只列出当前身份可见的数据集。 */
    @GetMapping("/datasets")
    public List<DatasetStore.Summary> datasets(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return datasetStore.list(actor.userId());
    }

    /** 复用已缓存的结果集，不重新查询；跨身份读取一律拒绝。 */
    @GetMapping("/datasets/{id}")
    public QueryResponse dataset(@PathVariable String id,
                                 @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return datasetStore.get(id, actor.userId());
    }
}
