package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import com.queryon.web.share.ShareStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 分享链接（D3-07）：保存查询定义并按创建者身份复用，不扩大数据可见范围（G4）。 */
@RestController
@RequestMapping("/api/shares")
public class ShareController {

    public record ShareRequest(String name, QueryRequest query) {
    }

    private final ShareStore store;
    private final QueryService queryService;
    private final SqlSemanticReader sqlSemanticReader;
    private final IdentityResolver identityResolver;

    public ShareController(ShareStore store, QueryService queryService,
                           SqlSemanticReader sqlSemanticReader, IdentityResolver identityResolver) {
        this.store = store;
        this.queryService = queryService;
        this.sqlSemanticReader = sqlSemanticReader;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public ShareStore.SharedQuery create(
            @RequestBody ShareRequest request,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.save(request.name(), request.query(), actor.userId());
    }

    @GetMapping
    public List<ShareStore.SharedQuery> list(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.list(actor.userId());
    }

    @GetMapping("/{token}")
    public QueryResponse resolve(
            @PathVariable String token,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        ShareStore.SharedQuery shared = store.get(token, actor.userId());
        QueryRequest request = shared.query();
        SemanticQuery query = request.sql() != null && !request.sql().isBlank()
                ? sqlSemanticReader.read(request.sql())
                : request.toSemanticQuery(LocalDate.now());
        return QueryResponse.from(queryService.execute(query, actor));
    }

    @DeleteMapping("/{token}")
    public Map<String, String> revoke(
            @PathVariable String token,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        store.revoke(token, actor.userId());
        return Map.of("status", "已撤销", "token", token);
    }
}
