package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.web.async.AsyncQueryStore;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 异步长查询入口（D3-08）：提交、轮询进度/结果、取消；全部按身份隔离。 */
@RestController
@RequestMapping("/api/queries/async")
public class AsyncQueryController {

    private final AsyncQueryStore store;
    private final IdentityResolver identityResolver;

    public AsyncQueryController(AsyncQueryStore store, IdentityResolver identityResolver) {
        this.store = store;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public AsyncQueryStore.TaskView submit(
            @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.submit(request, actor);
    }

    @GetMapping("/{id}")
    public AsyncQueryStore.TaskView status(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.get(id, actor);
    }

    @DeleteMapping("/{id}")
    public AsyncQueryStore.TaskView cancel(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.cancel(id, actor);
    }
}
