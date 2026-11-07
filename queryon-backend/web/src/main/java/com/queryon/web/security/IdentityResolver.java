package com.queryon.web.security;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;

import java.util.List;
import java.util.Map;

/**
 * 内置账号目录（Q15）：按提问者身份解析权限上下文，口令不写入仓库。
 * 后续按未决项触发结果接入 Spring Security 与预留的 OIDC，这里只保留身份契约。
 */
public final class IdentityResolver {

    private static final Map<String, Actor> ACCOUNTS = Map.of(
            Actor.ROLE_ADMIN_BI, Actor.adminBi(),
            Actor.ROLE_ANALYST_EAST, Actor.analystEast());

    public Actor resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new QueryonException(ErrorCode.UNAUTHENTICATED, "鉴权入口",
                    "未识别提问者身份", "请在请求头 X-Queryon-User 中指定内置账号");
        }
        Actor actor = ACCOUNTS.get(userId.trim());
        if (actor == null) {
            throw new QueryonException(ErrorCode.UNAUTHENTICATED, "鉴权入口",
                    "账号不在内置目录中", "请使用 GET /api/semantic/actors 查看可用内置账号");
        }
        return actor;
    }

    /**
     * 仅全量基准身份可执行的维护操作（连接管理、指标口径审批与回滚）。
     * 权限不足返回结构化错误，不泄露连接参数或口径内容。
     */
    public Actor resolveAdmin(String userId) {
        Actor actor = resolve(userId);
        if (!Actor.ROLE_ADMIN_BI.equals(actor.role())) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "权限校验",
                    "当前身份无权执行该维护操作",
                    "请切换到 admin_bi（全量基准身份）后重试");
        }
        return actor;
    }

    /** 只返回账号与角色说明，不返回任何口令或凭据。 */
    public List<Map<String, String>> accounts() {
        return List.of(
                Map.of("userId", Actor.ROLE_ADMIN_BI, "role", Actor.ROLE_ADMIN_BI,
                        "description", "全量基准身份"),
                Map.of("userId", Actor.ROLE_ANALYST_EAST, "role", Actor.ROLE_ANALYST_EAST,
                        "description", "行级华东、敏感列屏蔽"));
    }
}
