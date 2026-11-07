package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.QueryService;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.SqlDialect;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.web.dataservice.DataServiceMeter;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import com.queryon.web.security.IdentityResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数据服务 API（`D8-09`）：把受管指标与统一查询以稳定契约对外开放。
 *
 * <p>鉴权使用服务密钥（仅从环境变量读取，缺失时端点整体关闭），调用方必须显式声明
 * {@code subject}，执行仍以该内置账号身份注入行列级权限与审计；本端点不提供配额或限流
 * （按 `05` 第 4 节属企业版 `D7-07`），只做可计量统计。
 */
@RestController
@RequestMapping("/api/data-service")
public class DataServiceController {

    public record ServiceQuery(String subject, List<String> metrics, List<String> dimensions,
                               String timeWindow, Map<String, String> filters, Integer limit) {

        QueryRequest toRequest() {
            return new QueryRequest(metrics, dimensions, timeWindow, filters, limit, null);
        }
    }

    public record ServiceResult(String requestId, String subject, String servedAt, long elapsedMs,
                                int rows, QueryResponse result) {
    }

    public record Catalog(List<MetricDefinition> metrics, List<DimensionDefinition> dimensions,
                          List<String> dialects) {
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String KEY_HEADER = "X-Queryon-Service-Key";

    private final String apiKey;
    private final QueryService queryService;
    private final IdentityResolver identityResolver;
    private final DataServiceMeter meter;
    private final SemanticRegistry registry;

    public DataServiceController(@Value("${queryon.service.api-key:}") String apiKey,
                                 QueryService queryService, IdentityResolver identityResolver,
                                 DataServiceMeter meter, SemanticRegistry registry) {
        this.apiKey = apiKey;
        this.queryService = queryService;
        this.identityResolver = identityResolver;
        this.meter = meter;
        this.registry = registry;
    }

    /** 指标与维度目录：外部系统据此选择受管对象，不暴露物理表。 */
    @GetMapping("/metrics")
    public Catalog catalog(@RequestHeader(value = KEY_HEADER, required = false) String key) {
        requireKey(key);
        return new Catalog(registry.metricList(), registry.dimensionList(),
                Arrays.stream(SqlDialect.values()).map(Enum::name).toList());
    }

    @PostMapping("/query")
    public ServiceResult query(@RequestBody ServiceQuery request,
                               @RequestHeader(value = KEY_HEADER, required = false) String key,
                               @RequestHeader(value = "X-Queryon-Client", required = false) String client) {
        requireKey(key);
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            throw new QueryonException(ErrorCode.PARSE_GATE_REJECT, "数据服务",
                    "缺少调用主体 subject",
                    "请显式声明内置账号作为 subject，权限、脱敏与审计按该身份执行");
        }
        Actor actor;
        try {
            actor = identityResolver.resolve(request.subject());
        } catch (QueryonException exception) {
            meter.record(request.subject(), client, "拒绝", 0, 0);
            throw exception;
        }
        long start = System.nanoTime();
        try {
            QueryResponse response = QueryResponse.from(
                    queryService.execute(request.toRequest().toSemanticQuery(LocalDate.now()), actor));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            int rows = response.resultSet().rows().size();
            DataServiceMeter.Call call = meter.record(actor.userId(), client, "成功", rows, elapsed);
            return new ServiceResult(call.requestId(), actor.userId(),
                    LocalDateTime.now().format(TIME_FORMAT), elapsed, rows, response);
        } catch (RuntimeException exception) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            meter.record(actor.userId(), client, "拒绝", 0, elapsed);
            throw exception;
        }
    }

    /** 计量概览：调用次数、按主体分布与最近调用（不含请求体与密钥）。 */
    @GetMapping("/usage")
    public DataServiceMeter.Usage usage(
            @RequestHeader(value = KEY_HEADER, required = false) String key) {
        requireKey(key);
        return meter.usage();
    }

    private void requireKey(String provided) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "数据服务",
                    "数据服务未启用", "请以环境变量 QUERYON_SERVICE_API_KEY 配置服务密钥后重启");
        }
        if (provided == null || !MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "数据服务",
                    "服务密钥无效", "请在请求头 " + KEY_HEADER + " 中提供有效密钥");
        }
    }

}
