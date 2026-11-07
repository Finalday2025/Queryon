package com.queryon.semantic;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.model.MetricDefinition;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标版本化与审批（`D2-06`）：口径变更可 review、可 diff、可回滚。
 *
 * <p>版本历史只追加不删除：提交产生草稿，审批通过后替换语义层当前口径并记录审计；
 * 回滚不修改历史，而是以目标版本内容追加一个新版本并立即生效。
 * 所有校验以语义层注册表为准，不新增指标编码（G1）。
 */
public final class MetricVersionStore {

    public record VersionView(long id, String code, String version, String businessName,
                              String definition, String expression, String defaultFilter,
                              String unit, List<String> aliases, String status, String note,
                              String author, String createdAt, boolean active) {
    }

    /** 提交草稿：留空字段沿用当前口径，便于只改其中一项。 */
    public record Proposal(String businessName, String definition, String expression,
                           String defaultFilter, String unit, List<String> aliases, String note) {
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SemanticRegistry registry;
    private final AuditLog auditLog;
    private final Map<String, List<VersionView>> history = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public MetricVersionStore(SemanticRegistry registry, AuditLog auditLog) {
        this.registry = registry;
        this.auditLog = auditLog;
    }

    public List<VersionView> history(String code) {
        return List.copyOf(historyOf(code));
    }

    public synchronized VersionView propose(String code, Proposal proposal, String actorId) {
        List<VersionView> versions = historyOf(code);
        VersionView current = activeOf(versions);
        String businessName = pick(proposal == null ? null : proposal.businessName(),
                current.businessName());
        String definition = pick(proposal == null ? null : proposal.definition(), current.definition());
        String expression = pick(proposal == null ? null : proposal.expression(), current.expression());
        String defaultFilter = pick(proposal == null ? null : proposal.defaultFilter(),
                current.defaultFilter());
        String unit = pick(proposal == null ? null : proposal.unit(), current.unit());
        List<String> aliases = proposal == null || proposal.aliases() == null
                || proposal.aliases().isEmpty() ? current.aliases() : List.copyOf(proposal.aliases());
        String note = pick(proposal == null ? null : proposal.note(), "未填写变更说明");

        VersionView draft = new VersionView(sequence.incrementAndGet(), code, nextVersion(versions),
                businessName, definition, expression, defaultFilter, unit, aliases,
                "DRAFT", note, actorId, now(), false);
        versions.add(draft);
        audit(actorId, "指标版本·提交草稿", "成功", code + " " + draft.version());
        return draft;
    }

    /** 审批草稿：通过后立即成为语义层当前口径，原版本置为 SUPERSEDED。 */
    public synchronized VersionView approve(String code, String version, String actorId) {
        List<VersionView> versions = historyOf(code);
        int index = indexOf(versions, version);
        VersionView target = versions.get(index);
        if (!"DRAFT".equals(target.status())) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "指标审批",
                    "只能审批草稿版本", "请先提交草稿，或对已生效的历史版本执行回滚");
        }
        VersionView current = activeOf(versions);
        if (current != null) {
            versions.set(versions.indexOf(current), withStatus(current, "SUPERSEDED", false));
        }
        VersionView approved = withStatus(target, "APPROVED", true);
        versions.set(index, approved);
        apply(approved);
        audit(actorId, "指标版本·审批通过", "成功", code + " " + approved.version());
        return approved;
    }

    /** 回滚：以历史版本内容追加新版本并立即生效，历史记录保持可追溯。 */
    public synchronized VersionView rollback(String code, String version, String actorId) {
        List<VersionView> versions = historyOf(code);
        VersionView target = versions.get(indexOf(versions, version));
        if ("DRAFT".equals(target.status())) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "指标回滚",
                    "草稿版本尚未生效，不能回滚到草稿", "请先审批该草稿，或选择已生效的历史版本");
        }
        VersionView current = activeOf(versions);
        if (current != null && current.version().equals(target.version())) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "指标回滚",
                    "目标版本已是当前生效口径", "请选择其他历史版本");
        }
        if (current != null) {
            versions.set(versions.indexOf(current), withStatus(current, "SUPERSEDED", false));
        }
        VersionView restored = new VersionView(sequence.incrementAndGet(), code, nextVersion(versions),
                target.businessName(), target.definition(), target.expression(), target.defaultFilter(),
                target.unit(), target.aliases(), "APPROVED", "回滚自 " + target.version(),
                actorId, now(), true);
        versions.add(restored);
        apply(restored);
        audit(actorId, "指标版本·回滚", "成功", code + " " + restored.version() + " ← " + version);
        return restored;
    }

    private List<VersionView> historyOf(String code) {
        registry.requireMetric(code);
        return history.computeIfAbsent(code, key -> {
            MetricDefinition metric = registry.requireMetric(key);
            List<VersionView> seeded = new ArrayList<>();
            seeded.add(new VersionView(sequence.incrementAndGet(), key, metric.version(),
                    metric.businessName(), metric.definition(), metric.expression(),
                    metric.defaultFilter(), metric.unit(), metric.aliases(),
                    "APPROVED", "语义层初始口径", "system", now(), true));
            return seeded;
        });
    }

    private void apply(VersionView version) {
        registry.replaceMetric(new MetricDefinition(version.code(), version.businessName(),
                version.definition(), version.expression(), version.defaultFilter(), version.unit(),
                version.aliases(), version.version()));
    }

    private VersionView activeOf(List<VersionView> versions) {
        return versions.stream().filter(VersionView::active).findFirst().orElseThrow(() ->
                new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "指标版本",
                        "指标缺少生效版本", "请重新初始化语义层或审批一个草稿版本"));
    }

    private int indexOf(List<VersionView> versions, String version) {
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).version().equals(version)) {
                return i;
            }
        }
        throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "指标版本",
                "版本 " + version + " 不存在", "请刷新版本列表后重试");
    }

    /** 取已有版本号最大值加一，避免回滚追加时编号复用。 */
    private String nextVersion(List<VersionView> versions) {
        int max = 0;
        for (VersionView version : versions) {
            String digits = version.version().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                max = Math.max(max, Integer.parseInt(digits));
            }
        }
        return "v" + (max + 1);
    }

    private VersionView withStatus(VersionView source, String status, boolean active) {
        return new VersionView(source.id(), source.code(), source.version(), source.businessName(),
                source.definition(), source.expression(), source.defaultFilter(), source.unit(),
                source.aliases(), status, source.note(), source.author(), source.createdAt(), active);
    }

    private void audit(String actorId, String action, String outcome, String detail) {
        String traceId = "ver-" + UUID.randomUUID().toString().substring(0, 8);
        auditLog.record(new AuditEvent(traceId, actorId, "指标版本", action, outcome, detail));
    }

    private static String pick(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
