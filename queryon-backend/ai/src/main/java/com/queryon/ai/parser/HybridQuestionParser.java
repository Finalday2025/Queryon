package com.queryon.ai.parser;

import com.queryon.ai.adapter.ChatModelPort;
import com.queryon.ai.conversation.ConversationMemory;
import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.StructuredError;
import com.queryon.semantic.SchemaLinkResult;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混合解析（D4-03）：术语库与 Schema Linking 走确定性规则路径，规则无法确定时
 * 交给可插拔模型端口；模型降级或未命中认证口径时反问澄清，不臆测公式（G3）。
 *
 * <p>优先级：写操作 → 越权 → 私有口径 → 字段幻觉 → 口径说明 → 歧义 → 语义查询。
 */
public final class HybridQuestionParser {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");
    private static final Pattern TOP_N = Pattern.compile("前\\s*(\\d+)|TOP\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> WRITE_INTENTS = Set.of(
            "删除", "更新", "修改", "改成", "写入", "插入", "新增", "清空", "帮我改", "顺便帮我更新");
    private static final Set<String> KNOWN_TOKENS = Set.of(
            "GMV", "LTV", "SQL", "API", "PG", "TOP", "null", "paid", "refunded", "cancelled");
    private static final List<String> TERRITORIES = List.of("华东", "华北", "华南", "华中", "西南");
    private static final int MAX_MODEL_METRICS = 2;

    private final SemanticRegistry registry;
    private final ChatModelPort modelPort;
    private final ConversationMemory memory;

    public HybridQuestionParser(SemanticRegistry registry, ChatModelPort modelPort,
                                ConversationMemory memory) {
        this.registry = registry;
        this.modelPort = modelPort;
        this.memory = memory;
    }

    public UnderstandingResult parse(String question, String sessionId, Actor actor, LocalDate today) {
        String text = question == null ? "" : question.trim();
        SchemaLinkResult link = registry.link(text);

        Optional<StructuredError> earlyReject = earlyReject(text, actor, link);
        if (earlyReject.isPresent()) {
            return UnderstandingResult.rejected(earlyReject.get(), link);
        }
        if (isDefinitionQuestion(text)) {
            return UnderstandingResult.definition(link, definitionNotes(link));
        }
        if (link.hasAmbiguity()) {
            return UnderstandingResult.clarification(
                    "「" + String.join("、", link.ambiguousTerms()) + "」没有唯一认证口径，"
                            + "请确认要查询的受管指标或时间口径（例如净营收、销量、上月）", link);
        }

        SemanticQuery previous = memory.recall(sessionId).orElse(SemanticQuery.empty());
        List<String> metrics = new ArrayList<>(link.metricCodes());
        if (metrics.isEmpty() && !previous.metrics().isEmpty()) {
            metrics.addAll(previous.metrics());
        }
        if (metrics.isEmpty()) {
            metrics.addAll(modelAssist(link));
        }
        if (metrics.isEmpty()) {
            return UnderstandingResult.clarification(
                    "未命中受管指标，请明确要查询的认证指标：净营收、订单数、客单价或销量", link);
        }

        List<String> dimensions = parseDimensions(text);
        if (dimensions.isEmpty()) {
            dimensions = new ArrayList<>(previous.dimensions());
        }
        TimeWindow window = parseTimeWindow(text, today).orElse(previous.timeWindow());
        Map<String, String> filters = parseRegionFilter(text);
        if (filters.isEmpty()) {
            filters = new LinkedHashMap<>(previous.filters());
        }
        int limit = parseTopN(text).orElse(previous.limit());
        boolean descending = text.contains("排行") || text.contains("前") || text.contains("降序");

        SemanticQuery query = SemanticQuery.empty()
                .withMetrics(metrics)
                .withDimensions(dimensions)
                .withTimeWindow(window)
                .withLimit(limit);
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            query = query.withFilter(filter.getKey(), filter.getValue());
        }
        if (descending && !metrics.isEmpty()) {
            query = query.withOrder(metrics.getFirst(), true);
        }
        return UnderstandingResult.query(query, link);
    }

    /** 前置拦截：写操作、越权、私有口径、字段幻觉，均返回结构化错误且不执行。 */
    private Optional<StructuredError> earlyReject(String text, Actor actor, SchemaLinkResult link) {
        for (String intent : WRITE_INTENTS) {
            if (text.contains(intent)) {
                return Optional.of(StructuredError.of(ErrorCode.WRITE_OPERATION_REJECT, "解析门",
                        "识别到写操作意图", "所有 AI 输出限定为查询与展示，写操作一律拒绝且不执行"));
            }
        }
        for (String field : link.fieldNames()) {
            if (actor.isColumnDenied(field)) {
                return Optional.of(StructuredError.of(ErrorCode.PERMISSION_DENIED, "权限注入",
                        "当前身份无权访问敏感列 " + field, "请更换有权限的身份，或改用非敏感的受管指标"));
            }
        }
        if (!link.privateCaliberTerms().isEmpty()) {
            return Optional.of(StructuredError.of(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "请求使用私有口径：" + String.join("、", link.privateCaliberTerms()),
                    "语义层只提供唯一认证口径，可在口径面板查看定义"));
        }
        for (String term : findUnknownIdentifiers(text)) {
            boolean fieldLike = text.contains("字段") || text.contains("的平均值");
            ErrorCode code = fieldLike ? ErrorCode.SCHEMA_GATE_REJECT : ErrorCode.UNKNOWN_TERM;
            String gate = fieldLike ? "Schema 门" : "语义门";
            return Optional.of(StructuredError.of(code, gate,
                    "「" + term + "」不是受管字段或认证指标",
                    "请从指标目录与维度目录中选择，系统不会编造字段或猜测公式"));
        }
        return Optional.empty();
    }

    private List<String> findUnknownIdentifiers(String text) {
        List<String> unknown = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (KNOWN_TOKENS.contains(candidate) || registry.hasField(candidate) || registry.hasMetric(candidate)
                    || registry.dimension(candidate) != null) {
                continue;
            }
            unknown.add(candidate);
        }
        return unknown;
    }

    private List<String> modelAssist(SchemaLinkResult link) {
        if (!modelPort.available()) {
            return List.of();
        }
        ChatModelPort.ModelCompletion completion = modelPort.complete(
                new ChatModelPort.ModelRequest("受管指标选择", link.matchedTerms()));
        if (!completion.fromModel() || completion.content().isBlank()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : completion.content().split("[,，;；\\s]+")) {
            String code = candidate.trim();
            if (registry.hasMetric(code) && result.size() < MAX_MODEL_METRICS) {
                result.add(code);
            }
        }
        return new ArrayList<>(result);
    }

    private boolean isDefinitionQuestion(String text) {
        return registry.definitionQuestionTerms().stream().anyMatch(text::contains);
    }

    private List<String> definitionNotes(SchemaLinkResult link) {
        List<String> notes = new ArrayList<>();
        List<String> codes = link.metricCodes().isEmpty()
                ? List.of(SemanticRegistry.METRIC_NET_REVENUE) : link.metricCodes();
        for (String code : codes) {
            MetricDefinition metric = registry.metric(code);
            if (metric != null) {
                notes.add(metric.businessName() + "：" + metric.definition()
                        + "｜公式 " + metric.expression()
                        + "｜过滤 " + metric.defaultFilter()
                        + "｜版本 " + metric.version());
            }
        }
        return notes;
    }

    private static List<String> parseDimensions(String text) {
        List<String> dimensions = new ArrayList<>();
        if (containsAny(text, "大区", "区域", "片区", "地区", "按地区", "按销售大区")) {
            dimensions.add(SemanticRegistry.COL_SALES_TERRITORY);
        }
        if (containsAny(text, "品类", "类别", "类目", "品线")) {
            dimensions.add(SemanticRegistry.COL_CATEGORY);
        }
        if (text.contains("品牌")) {
            dimensions.add("brand");
        }
        if (text.contains("客户等级") || text.contains("会员等级")) {
            dimensions.add("customer_level");
        }
        return dimensions;
    }

    private static Optional<TimeWindow> parseTimeWindow(String text, LocalDate today) {
        if (text.contains("上月") || text.contains("上个月") || text.contains("上一自然月")) {
            return Optional.of(TimeWindow.lastMonth(today));
        }
        if (text.contains("本月") || text.contains("这个月") || text.contains("当月")) {
            return Optional.of(TimeWindow.monthToDate(today));
        }
        if (text.contains("今年累计") || text.contains("本年至今") || text.contains("今年") || text.contains("本年")) {
            return Optional.of(TimeWindow.yearToDate(today));
        }
        return Optional.empty();
    }

    private static Map<String, String> parseRegionFilter(String text) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String territory : TERRITORIES) {
            if (text.contains(territory)) {
                filters.put(SemanticRegistry.COL_SALES_TERRITORY, territory);
            }
        }
        return filters;
    }

    private static Optional<Integer> parseTopN(String text) {
        Matcher matcher = TOP_N.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Optional.of(Math.min(Math.max(Integer.parseInt(value), 1), 1000));
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
