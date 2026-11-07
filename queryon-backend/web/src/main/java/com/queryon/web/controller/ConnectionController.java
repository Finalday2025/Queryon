package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.web.connection.ConnectionStore;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源连接管理（`D1-01`、`D1-02`）：
 * 列表与连通性测试对所有内置账号开放，新增／编辑／启停／删除仅限全量基准身份，
 * 凭据只以密文保存、接口不回传，测试连接只探测端口可达性。
 */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionStore store;
    private final IdentityResolver identityResolver;

    public ConnectionController(ConnectionStore store, IdentityResolver identityResolver) {
        this.store = store;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<ConnectionStore.View> list(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return store.list();
    }

    @PostMapping
    public ConnectionStore.View create(
            @RequestBody ConnectionStore.Input input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = requireManager(userId);
        return store.create(input, actor.userId());
    }

    @PutMapping("/{id}")
    public ConnectionStore.View update(
            @PathVariable String id,
            @RequestBody ConnectionStore.Input input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = requireManager(userId);
        return store.update(id, input, actor.userId());
    }

    @PostMapping("/{id}/test")
    public ConnectionStore.View test(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return store.test(id, actor.userId());
    }

    @PostMapping("/{id}/toggle")
    public ConnectionStore.View toggle(
            @PathVariable String id,
            @RequestParam boolean enabled,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = requireManager(userId);
        return store.toggle(id, enabled, actor.userId());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = requireManager(userId);
        store.delete(id, actor.userId());
        return Map.of("status", "已删除", "id", id);
    }

    private Actor requireManager(String userId) {
        return identityResolver.resolveAdmin(userId);
    }
}
