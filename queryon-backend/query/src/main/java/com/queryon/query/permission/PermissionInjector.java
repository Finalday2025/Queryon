package com.queryon.query.permission;

import com.queryon.common.context.Actor;
import com.queryon.common.context.AskTrace;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.common.result.ResultSet;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.model.SemanticQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限随人（G4）：行列级过滤由系统在语义查询层注入，不写进生成侧提示词；
 * 敏感列请求在护栏之前直接结构化拒绝，不执行查询。
 */
public final class PermissionInjector {

    public SemanticQuery inject(SemanticQuery query, Actor actor, SchemaLinkResult link, AskTrace trace) {
        for (String field : link.fieldNames()) {
            if (actor.isColumnDenied(field)) {
                throw new QueryonException(ErrorCode.PERMISSION_DENIED, "权限注入",
                        "当前身份无权访问敏感列 " + field, "请更换有权限的身份，或改用非敏感的受管指标");
            }
        }
        for (String dimension : query.dimensions()) {
            if (actor.isColumnDenied(dimension)) {
                throw new QueryonException(ErrorCode.PERMISSION_DENIED, "权限注入",
                        "当前身份无权按敏感列 " + dimension + " 分组", "请改用受管的非敏感维度");
            }
        }
        SemanticQuery governed = query;
        for (Map.Entry<String, String> filter : actor.rowFilters().entrySet()) {
            governed = governed.withFilter(filter.getKey(), filter.getValue());
        }
        trace.record("权限注入", "行级过滤 " + actor.rowFilters().size() + " 项，敏感列校验通过");
        return governed;
    }

    /** 结果集出口再做一次列级掩码，覆盖所有入口。 */
    public ResultSet mask(ResultSet resultSet, Actor actor) {
        boolean needsMask = resultSet.columns().stream()
                .anyMatch(column -> actor.isColumnMasked(column) || actor.isColumnDenied(column));
        if (!needsMask) {
            return resultSet;
        }
        List<Map<String, Object>> maskedRows = new ArrayList<>();
        for (Map<String, Object> row : resultSet.rows()) {
            Map<String, Object> masked = new LinkedHashMap<>(row);
            for (String column : resultSet.columns()) {
                if (actor.isColumnDenied(column)) {
                    throw new QueryonException(ErrorCode.PERMISSION_DENIED, "权限注入",
                            "结果包含被屏蔽列 " + column, "请更换有权限的身份后重试");
                }
                if (actor.isColumnMasked(column)) {
                    masked.put(column, "***");
                }
            }
            maskedRows.add(masked);
        }
        return new ResultSet(resultSet.columns(), maskedRows, resultSet.totalRows(), resultSet.truncated());
    }
}
