package com.queryon.web.metadata;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticColumn;
import com.queryon.semantic.model.SemanticTable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务描述维护（`D1-04`）：表与字段的中文业务名、说明、同义词、敏感标记可编辑，
 * 变更追加到变更台账并可逐条回退；写操作仅限全量基准身份，全部留审计。
 *
 * <p>只维护描述元数据，不改物理结构、不改指标口径，也不允许新增字段或表。
 */
@Component
public class MetadataAdminService {

    /** 描述编辑入参：字段留空表示沿用原值。 */
    public record DescriptionInput(String businessName, String description,
                                   List<String> synonyms, Boolean sensitive) {
    }

    /** 变更台账：before/after 成对保存，支持按条回退。 */
    public record Change(long id, String scope, String table, String column,
                         String beforeBusinessName, String beforeDescription,
                         List<String> beforeSynonyms, boolean beforeSensitive,
                         String afterBusinessName, String afterDescription,
                         List<String> afterSynonyms, boolean afterSensitive,
                         String author, String createdAt) {
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SemanticRegistry registry;
    private final AuditLog auditLog;
    private final List<Change> changes = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public MetadataAdminService(SemanticRegistry registry, AuditLog auditLog) {
        this.registry = registry;
        this.auditLog = auditLog;
    }

    public List<Change> history() {
        List<Change> ordered = new ArrayList<>(changes);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    public Change updateTable(String tableName, DescriptionInput input, Actor actor) {
        SemanticTable before = registry.table(tableName);
        registry.overrideTableDescription(tableName, input == null ? null : input.businessName(),
                input == null ? null : input.description());
        SemanticTable after = registry.table(tableName);
        Change change = record("表", tableName, "",
                before.businessName(), before.description(), List.of(), false,
                after.businessName(), after.description(), List.of(), false, actor);
        audit(actor, "元数据·表描述", tableName);
        return change;
    }

    public Change updateColumn(String tableName, String columnName, DescriptionInput input,
                               Actor actor) {
        SemanticTable beforeTable = registry.table(tableName);
        SemanticColumn before = beforeTable.column(columnName).orElseThrow(() -> new QueryonException(
                ErrorCode.SCHEMA_GATE_REJECT, "元数据维护",
                "字段 " + columnName + " 不在表 " + tableName + " 的语义模型中",
                "请从字段目录中选择受管字段"));
        registry.overrideColumnDescription(tableName, columnName,
                input == null ? null : input.businessName(),
                input == null ? null : input.description(),
                input == null ? null : input.synonyms(),
                input == null ? null : input.sensitive());
        SemanticColumn after = registry.table(tableName).column(columnName).orElseThrow();
        Change change = record("字段", tableName, columnName,
                before.businessName(), before.description(), before.synonyms(), before.sensitive(),
                after.businessName(), after.description(), after.synonyms(), after.sensitive(), actor);
        audit(actor, "元数据·字段描述", tableName + "." + columnName);
        return change;
    }

    /** 按条回退：把该次变更的 before 值重新写回语义层，本次回退同样留审计。 */
    public Change revert(long changeId, Actor actor) {
        Change change = changes.stream().filter(item -> item.id() == changeId).findFirst()
                .orElseThrow(() -> new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "元数据维护",
                        "变更记录 " + changeId + " 不存在", "请刷新变更台账后重试"));
        if ("表".equals(change.scope())) {
            registry.overrideTableDescription(change.table(), change.beforeBusinessName(),
                    change.beforeDescription());
        } else {
            registry.overrideColumnDescription(change.table(), change.column(),
                    change.beforeBusinessName(), change.beforeDescription(),
                    change.beforeSynonyms(), change.beforeSensitive());
        }
        audit(actor, "元数据·回退", change.table()
                + (change.column().isEmpty() ? "" : "." + change.column()));
        return change;
    }

    private Change record(String scope, String table, String column,
                          String beforeName, String beforeDescription,
                          List<String> beforeSynonyms, boolean beforeSensitive,
                          String afterName, String afterDescription,
                          List<String> afterSynonyms, boolean afterSensitive, Actor actor) {
        Change change = new Change(sequence.incrementAndGet(), scope, table, column,
                beforeName, beforeDescription, List.copyOf(beforeSynonyms), beforeSensitive,
                afterName, afterDescription, List.copyOf(afterSynonyms), afterSensitive,
                actor.userId(), LocalDateTime.now().format(TIME_FORMAT));
        changes.add(change);
        return change;
    }

    private void audit(Actor actor, String action, String detail) {
        String traceId = "meta-" + UUID.randomUUID().toString().substring(0, 8);
        auditLog.record(new AuditEvent(traceId, actor.userId(), actor.role(), action, "成功", detail));
    }
}
