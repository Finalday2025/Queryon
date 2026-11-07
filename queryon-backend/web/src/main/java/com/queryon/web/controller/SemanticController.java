package com.queryon.web.controller;

import com.queryon.common.context.Actor;
import com.queryon.query.profile.DataProfileService;
import com.queryon.web.metadata.JdbcProfileService;
import com.queryon.web.metadata.MetadataAdminService;
import com.queryon.semantic.MetricVersionStore;
import com.queryon.semantic.SemanticRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.queryon.semantic.SemanticTranslator;
import com.queryon.semantic.SqlDialect;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.JoinPath;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.SemanticTable;
import com.queryon.web.dto.DialectTranslation;
import com.queryon.web.dto.DisplayMeta;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径可查（G5）：指标定义、维度、表与中文业务描述、批准 join 路径、内置账号目录与展示元数据。
 * 所有目录端点统一做身份解析，避免开放 API 出现鉴权旁路（D7-01）。
 */
@RestController
@RequestMapping("/api/semantic")
public class SemanticController {

    private final SemanticRegistry registry;
    private final IdentityResolver identityResolver;
    private final DataProfileService dataProfileService;
    private final ObjectProvider<JdbcProfileService> jdbcProfileProvider;
    private final MetricVersionStore versionStore;
    private final MetadataAdminService metadataAdminService;

    public SemanticController(SemanticRegistry registry, IdentityResolver identityResolver,
                              DataProfileService dataProfileService,
                              ObjectProvider<JdbcProfileService> jdbcProfileProvider,
                              MetricVersionStore versionStore,
                              MetadataAdminService metadataAdminService) {
        this.registry = registry;
        this.identityResolver = identityResolver;
        this.dataProfileService = dataProfileService;
        this.jdbcProfileProvider = jdbcProfileProvider;
        this.versionStore = versionStore;
        this.metadataAdminService = metadataAdminService;
    }

    @GetMapping("/metrics")
    public List<MetricDefinition> metrics(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return registry.metricList();
    }

    /**
     * 指标版本历史（`D2-06`）：提交草稿、审批生效、按历史版本回滚。
     * 读版本对全部内置账号开放（G5 口径可查），写操作仅限全量基准身份并留审计。
     */
    @GetMapping("/metrics/{code}/versions")
    public List<MetricVersionStore.VersionView> metricVersions(
            @PathVariable String code,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return versionStore.history(code);
    }

    @PostMapping("/metrics/{code}/versions")
    public MetricVersionStore.VersionView proposeMetricVersion(
            @PathVariable String code,
            @RequestBody MetricVersionStore.Proposal proposal,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return versionStore.propose(code, proposal, actor.userId());
    }

    @PostMapping("/metrics/{code}/versions/{version}/approve")
    public MetricVersionStore.VersionView approveMetricVersion(
            @PathVariable String code, @PathVariable String version,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return versionStore.approve(code, version, actor.userId());
    }

    @PostMapping("/metrics/{code}/versions/{version}/rollback")
    public MetricVersionStore.VersionView rollbackMetricVersion(
            @PathVariable String code, @PathVariable String version,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return versionStore.rollback(code, version, actor.userId());
    }

    /** 表／字段业务描述编辑（`D1-04`）：仅全量基准身份，变更可回退并留审计。 */
    @GetMapping("/metadata/changes")
    public List<MetadataAdminService.Change> metadataChanges(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return metadataAdminService.history();
    }

    @PutMapping("/tables/{table}/description")
    public MetadataAdminService.Change updateTableDescription(
            @PathVariable String table,
            @RequestBody MetadataAdminService.DescriptionInput input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return metadataAdminService.updateTable(table, input, actor);
    }

    @PutMapping("/tables/{table}/columns/{column}/description")
    public MetadataAdminService.Change updateColumnDescription(
            @PathVariable String table, @PathVariable String column,
            @RequestBody MetadataAdminService.DescriptionInput input,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return metadataAdminService.updateColumn(table, column, input, actor);
    }

    @PostMapping("/metadata/changes/{id}/revert")
    public MetadataAdminService.Change revertMetadataChange(
            @PathVariable long id,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolveAdmin(userId);
        return metadataAdminService.revert(id, actor);
    }

    @GetMapping("/dimensions")
    public List<DimensionDefinition> dimensions(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return registry.dimensionList();
    }

    @GetMapping("/tables")
    public List<SemanticTable> tables(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return registry.tableList();
    }

    @GetMapping("/joins")
    public List<JoinPath> joins(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return registry.approvedJoins();
    }

    @GetMapping("/actors")
    public List<Map<String, String>> actors(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return identityResolver.accounts();
    }

    /** 报表／结果表／交叉表共用的展示元数据：中文列名、指标单位与维度下钻路径。 */
    @GetMapping("/display")
    public DisplayMeta display(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        registry.metricList().forEach(metric -> {
            labels.put(metric.code(), metric.businessName());
            units.put(metric.code(), metric.unit());
        });
        registry.dimensionList().forEach(dimension -> labels.put(dimension.code(), dimension.businessName()));
        registry.tableList().forEach(table -> table.columns()
                .forEach(column -> labels.putIfAbsent(column.name(), column.businessName())));
        return new DisplayMeta(Map.copyOf(labels), Map.copyOf(units), registry.drillDown());
    }

    /** 数据预览与值画像（D1-06）：只接受语义层注册表内的表／字段。 */
    @GetMapping("/profile")
    public DataProfileService.ColumnProfile profile(
            @RequestParam String table,
            @RequestParam String column,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        JdbcProfileService jdbcProfile = jdbcProfileProvider.getIfAvailable();
        return jdbcProfile == null
                ? dataProfileService.profile(table, column)
                : jdbcProfile.profile(table, column);
    }

    /** 可回归的方言清单。 */
    @GetMapping("/dialects")
    public List<String> dialects(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return Arrays.stream(SqlDialect.values()).map(Enum::name).toList();
    }

    /**
     * 语义查询翻译：同一语义查询在各方言下的 SQL 快照与所用指标版本，
     * 供按方言分组回归（D6-08）与结论溯源（D6-06）；只翻译不执行，不返回任何业务数据。
     */
    @PostMapping("/translate")
    public List<DialectTranslation> translate(
            @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        SemanticQuery query = request.toSemanticQuery(LocalDate.now());
        SemanticTranslator translator = new SemanticTranslator(registry);
        List<String> versions = query.metrics().stream()
                .map(code -> code + "@" + registry.requireMetric(code).version())
                .toList();
        return Arrays.stream(SqlDialect.values())
                .map(dialect -> new DialectTranslation(dialect.name(),
                        translator.translate(query, dialect).sql(), versions))
                .toList();
    }
}
