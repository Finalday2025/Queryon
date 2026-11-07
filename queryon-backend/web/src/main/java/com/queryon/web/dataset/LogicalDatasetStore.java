package com.queryon.web.dataset;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.JoinPath;
import com.queryon.semantic.model.SemanticQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 逻辑数据集（`D1-05`）：从语义层裁剪出可授权的分析单元。
 *
 * <p>数据集只引用受管指标、受管维度与已批准的关联路径，不暴露物理表名，
 * 也不存任何 SQL；查询仍由统一查询入口按提问者身份执行，行／列级权限不因数据集而旁路。
 * 未标记共享的数据集仅创建者与全量基准身份可见、可改。
 */
@Component
public class LogicalDatasetStore {

    /**
     * @param metrics        受管指标子集（数据集内的计算字段）
     * @param dimensions     受管维度子集（字段裁剪后的可用维度）
     * @param joins          允许的关联路径，形如 {@code fromTable->toTable}
     * @param shared         是否共享给其他内置账号（权限仍随提问者）
     * @param defaultFilters 数据集级默认过滤，键必须是本数据集的维度
     */
    public record View(String id, String name, String description, List<String> metrics,
                       List<String> dimensions, List<String> joins, boolean shared,
                       Map<String, String> defaultFilters, String ownerId, String createdAt) {
    }

    /** 编辑入参：留空字段表示沿用原值由 {@link #update} 处理，创建时必填名称与指标。 */
    public record Input(String name, String description, List<String> metrics,
                        List<String> dimensions, List<String> joins, Boolean shared,
                        Map<String, String> defaultFilters) {
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SemanticRegistry registry;
    private final Map<String, View> datasets = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public LogicalDatasetStore(SemanticRegistry registry) {
        this.registry = registry;
    }

    public List<View> list(Actor actor) {
        return datasets.values().stream()
                .filter(dataset -> dataset.ownerId().equals(actor.userId()) || dataset.shared()
                        || isAdmin(actor))
                .sorted(Comparator.comparing(View::id))
                .toList();
    }

    public View create(Input input, Actor actor) {
        if (input == null) {
            throw invalid("缺少数据集定义", "请提交 name、metrics、dimensions 等字段");
        }
        String name = trim(input.name());
        if (name.isEmpty()) {
            throw invalid("数据集名称不能为空", "请填写便于识别的名称");
        }
        String id = "lds-" + sequence.incrementAndGet();
        View dataset = normalize(input, id, actor.userId(), LocalDateTime.now().format(TIME_FORMAT));
        datasets.put(id, dataset);
        return dataset;
    }

    public View update(String id, Input input, Actor actor) {
        View existing = requireWritable(id, actor);
        if (input == null) {
            throw invalid("缺少数据集定义", "请提交完整的 name、metrics、dimensions 等字段");
        }
        String name = trim(input.name());
        View updated = normalize(new Input(
                name.isEmpty() ? existing.name() : name,
                input.description() == null ? existing.description() : input.description(),
                input.metrics() == null ? existing.metrics() : input.metrics(),
                input.dimensions() == null ? existing.dimensions() : input.dimensions(),
                input.joins() == null ? existing.joins() : input.joins(),
                input.shared() == null ? existing.shared() : input.shared(),
                input.defaultFilters() == null ? existing.defaultFilters() : input.defaultFilters()),
                existing.id(), existing.ownerId(), existing.createdAt());
        datasets.put(id, updated);
        return updated;
    }

    public View delete(String id, Actor actor) {
        requireWritable(id, actor);
        return datasets.remove(id);
    }

    /** 读取：创建者、全量基准身份或共享数据集。 */
    public View accessible(String id, Actor actor) {
        View dataset = require(id);
        if (!dataset.shared() && !dataset.ownerId().equals(actor.userId()) && !isAdmin(actor)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "逻辑数据集",
                    "数据集未共享，当前身份无权使用",
                    "请让创建者共享该数据集，或切换到创建者／全量基准身份");
        }
        return dataset;
    }

    /** 查询范围校验：指标、维度与过滤列都必须落在数据集裁剪范围内。 */
    public void validateScope(View dataset, SemanticQuery query) {
        for (String metric : query.metrics()) {
            if (!dataset.metrics().contains(metric)) {
                throw outOfScope("指标", metric, dataset.metrics());
            }
        }
        for (String dimension : query.dimensions()) {
            if (!dataset.dimensions().contains(dimension)) {
                throw outOfScope("维度", dimension, dataset.dimensions());
            }
        }
        for (String column : query.filters().keySet()) {
            if (!dataset.dimensions().contains(column)) {
                throw outOfScope("过滤字段", column, dataset.dimensions());
            }
        }
    }

    private View normalize(Input input, String id, String ownerId, String createdAt) {
        List<String> metrics = distinct(input.metrics());
        if (metrics.isEmpty()) {
            throw invalid("数据集至少需要一个受管指标", "请从指标目录中选择认证口径");
        }
        metrics.forEach(registry::requireMetric);

        List<String> dimensions = distinct(input.dimensions());
        for (String code : dimensions) {
            DimensionDefinition dimension = registry.dimension(code);
            if (dimension == null) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "逻辑数据集",
                        "维度 " + code + " 不在语义模型内",
                        "请从维度目录中选择受管维度，数据集不允许直接引用物理列");
            }
        }

        List<String> joins = distinct(input.joins());
        for (String join : joins) {
            if (registry.approvedJoins().stream().noneMatch(path -> format(path).equals(join))) {
                throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "逻辑数据集",
                        "关联路径 " + join + " 未经批准",
                        "只允许使用语义层已批准的星型关联路径");
            }
        }

        Map<String, String> filters = new LinkedHashMap<>();
        if (input.defaultFilters() != null) {
            for (Map.Entry<String, String> entry : input.defaultFilters().entrySet()) {
                String column = trim(entry.getKey());
                String value = trim(entry.getValue());
                if (column.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (!dimensions.contains(column)) {
                    throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "逻辑数据集",
                            "默认过滤字段 " + column + " 不在数据集的维度范围内",
                            "请先在数据集中勾选该维度，或移除该默认过滤条件");
                }
                filters.put(column, value);
            }
        }

        return new View(id, trim(input.name()), input.description() == null ? "" : input.description().trim(),
                metrics, dimensions, joins, Boolean.TRUE.equals(input.shared()),
                Map.copyOf(filters), ownerId, createdAt);
    }

    private View require(String id) {
        View dataset = datasets.get(id);
        if (dataset == null) {
            throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "逻辑数据集",
                    "数据集不存在或已删除", "请刷新数据集列表后重试");
        }
        return dataset;
    }

    private View requireWritable(String id, Actor actor) {
        View dataset = require(id);
        if (!dataset.ownerId().equals(actor.userId()) && !isAdmin(actor)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "逻辑数据集",
                    "仅创建者或全量基准身份可以修改该数据集",
                    "共享数据集对所有账号可读，但编辑权仍归创建者");
        }
        return dataset;
    }

    private QueryonException outOfScope(String kind, String code, List<String> allowed) {
        return new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "逻辑数据集",
                kind + " " + code + " 不在该数据集的裁剪范围内",
                "可用" + kind + "：" + (allowed.isEmpty() ? "（无，请先补充数据集定义）" : String.join("／", allowed)));
    }

    private QueryonException invalid(String reason, String hint) {
        return new QueryonException(ErrorCode.PARSE_GATE_REJECT, "逻辑数据集", reason, hint);
    }

    private static boolean isAdmin(Actor actor) {
        return Actor.ROLE_ADMIN_BI.equals(actor.role());
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList()));
    }

    private static String format(JoinPath path) {
        return path.fromTable() + "->" + path.toTable();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
