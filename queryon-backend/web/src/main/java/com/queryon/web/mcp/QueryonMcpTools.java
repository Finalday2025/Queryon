package com.queryon.web.mcp;

import com.queryon.ai.AskResponse;
import com.queryon.ai.AskService;
import com.queryon.common.context.Actor;
import com.queryon.query.QueryOutcome;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具暴露层（M2 已决：MCP 适配层留在 M2，REST + MCP 同期）。
 * 两个工具直接复用 {@link AskService} 与 {@link QueryService} 的受治理查询入口，
 * 与 REST 的 /api/ask、/api/queries 走同一语义层、四道护栏与权限注入，口径不会分裂。
 * 身份由调用方显式传入内置账号（userId），等价于 REST 的 X-Queryon-User 头。
 */
@Component
public class QueryonMcpTools {

    private final AskService askService;
    private final QueryService queryService;
    private final SqlSemanticReader sqlSemanticReader;
    private final IdentityResolver identityResolver;
    private final ObjectMapper objectMapper;

    public QueryonMcpTools(AskService askService, QueryService queryService,
                           SqlSemanticReader sqlSemanticReader, IdentityResolver identityResolver,
                           ObjectMapper objectMapper) {
        this.askService = askService;
        this.queryService = queryService;
        this.sqlSemanticReader = sqlSemanticReader;
        this.identityResolver = identityResolver;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "用自然语言向 Queryon 问数。返回指标结果、推荐图表类型与认证口径说明；" +
            "歧义会要求澄清，越权或不可解析会被护栏拦截。userId 决定行列级权限。")
    public String ask(
            @ToolParam(description = "自然语言问句，例如「上月华东净营收」") String question,
            @ToolParam(description = "内置账号 identity，决定权限；可选 admin_bi（全量）或 analyst_east（华东·敏感列屏蔽）") String userId,
            @ToolParam(description = "多轮会话标识，可空；用于继承上下文", required = false) String sessionId) {
        Actor actor = identityResolver.resolve(userId);
        AskResponse resp = askService.ask(question, actor, sessionId);
        // AskResponse 在 ANSWERED/DEFINITION/REJECTED 等不同状态下，chartType/clarification/error 常为 null；
        // Map.of 不接受 null 值，会抛 NPE 并被 MCP 层包成空的 isError，故用允许 null 的 LinkedHashMap。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status().name());
        out.put("traceId", resp.traceId());
        out.put("chartType", resp.chartType());
        out.put("sqlSnapshot", resp.sqlSnapshot());
        out.put("resultSet", resp.resultSet());
        out.put("metricDefinitions", resp.metricDefinitions());
        out.put("clarification", resp.clarification());
        out.put("rejectedReason", resp.error() == null ? null : resp.error().toString());
        out.put("steps", resp.steps() == null ? List.of()
                : resp.steps().stream().map(Object::toString).toList());
        out.put("interpretation", resp.interpretation() == null ? List.of() : resp.interpretation());
        out.put("suggestions", resp.suggestions() == null ? List.of() : resp.suggestions());
        return writeJson(out);
    }

    @Tool(description = "执行结构化语义查询或 SQL。metrics/dimensions/timeWindow/filters/limit 描述语义查询，" +
            "或提交 sql 走 SQL 编辑器同一路径；二者都归一到受治理查询入口，权限与护栏一致。")
    public String query(
            @ToolParam(description = "指标编码列表，如 [\"net_revenue\"]", required = false) List<String> metrics,
            @ToolParam(description = "维度编码列表，如 [\"region\"]，可空", required = false) List<String> dimensions,
            @ToolParam(description = "时间窗：ALL / LAST_MONTH / YEAR_TO_DATE，可空", required = false) String timeWindow,
            @ToolParam(description = "维度过滤，如 {\"region\":\"华东\"}，可空", required = false) Map<String, String> filters,
            @ToolParam(description = "返回行数上限，可空", required = false) Integer limit,
            @ToolParam(description = "原始 SQL；若提供则优先按 SQL 路径执行，可空", required = false) String sql,
            @ToolParam(description = "内置账号 identity，决定权限；可选 admin_bi 或 analyst_east") String userId) {
        Actor actor = identityResolver.resolve(userId);
        SemanticQuery semanticQuery = (sql != null && !sql.isBlank())
                ? sqlSemanticReader.read(sql)
                : new QueryRequest(metrics, dimensions, timeWindow, filters, limit, null)
                    .toSemanticQuery(LocalDate.now());
        QueryOutcome outcome = queryService.execute(semanticQuery, actor);
        QueryResponse qr = QueryResponse.from(outcome);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultSet", qr.resultSet());
        out.put("sql", qr.sql());
        out.put("metricDefinitions", qr.metricDefinitions());
        out.put("traceId", qr.traceId());
        out.put("steps", qr.steps() == null ? List.of()
                : qr.steps().stream().map(Object::toString).toList());
        return writeJson(out);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败:" + e.getMessage() + "\"}";
        }
    }
}
