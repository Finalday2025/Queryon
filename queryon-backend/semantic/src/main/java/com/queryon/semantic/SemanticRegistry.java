package com.queryon.semantic;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.semantic.model.DimensionDefinition;
import com.queryon.semantic.model.JoinPath;
import com.queryon.semantic.model.MetricDefinition;
import com.queryon.semantic.model.SemanticColumn;
import com.queryon.semantic.model.SemanticTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义层注册中心：5 张物理表、中文业务描述与同义词、4 个受管指标的唯一认证口径、
 * 受管维度与批准的星型 join 路径。AI 只能命中这里的受管对象（G1）。
 *
 * <p>表名、字段名与中文业务名沿用 05 第 8.1／8.2 节，不另造同义词。
 */
public final class SemanticRegistry {

    public static final String FACT_ORDER = "fact_order";
    public static final String FACT_ORDER_ITEM = "fact_order_item";
    public static final String DIM_REGION = "dim_region";
    public static final String DIM_CUSTOMER = "dim_customer";
    public static final String DIM_PRODUCT = "dim_product";

    public static final String COL_ORDER_DATE = "order_date";
    public static final String COL_ORDER_STATUS = "order_status";
    public static final String COL_SALES_TERRITORY = "sales_territory";
    public static final String COL_CATEGORY = "category";

    public static final String METRIC_NET_REVENUE = "net_revenue";
    public static final String METRIC_ORDER_COUNT = "order_count";
    public static final String METRIC_AVG_ORDER_VALUE = "avg_order_value";
    public static final String METRIC_SALES_VOLUME = "sales_volume";

    /** 维度下钻路径：只用 05 第 8.1／8.2 节既有编码，不新增维度或同义词。 */
    private static final Map<String, String> DRILL_DOWN = Map.of(
            COL_SALES_TERRITORY, "province",
            "province", "region_name",
            COL_CATEGORY, "brand",
            "brand", "product_name");

    private static final SemanticRegistry STANDARD = new SemanticRegistry();

    /** 表与字段描述可维护（D1-04），每次替换整份不可变快照。 */
    private volatile Map<String, SemanticTable> tables;
    /** 指标口径可随版本审批／回滚替换，读取方始终看到同一份不可变快照。 */
    private volatile Map<String, MetricDefinition> metrics;
    private volatile Map<String, DimensionDefinition> dimensions;
    private final List<JoinPath> approvedJoins;
    private volatile Map<String, String> aliasToMetric;
    private volatile Map<String, String> aliasToDimension;
    private final Set<String> ambiguousTerms;
    private final Set<String> privateCaliberTerms;
    private final Set<String> definitionQuestionTerms;
    private final Set<String> rollingTimeTerms;

    private SemanticRegistry() {
        this.tables = buildTables();
        this.metrics = buildMetrics();
        this.dimensions = buildDimensions();
        this.approvedJoins = buildJoins();

        this.aliasToMetric = buildAliasToMetric(metrics);
        this.aliasToDimension = buildAliasToDimension(dimensions);

        // 8.2 节的业务黑话与易混词：无认证口径，命中即澄清（G3）
        this.ambiguousTerms = Set.of(
                "销售额", "成交额", "收款额", "GMV", "gmv", "毛利", "LTV", "客单金额");
        // 私有口径改写请求：语义门拒绝，不私自改写（D6-03）
        this.privateCaliberTerms = Set.of(
                "含运费的金额", "按含运费", "按客户算", "按另一种算法", "再算一次",
                "自定义口径", "换个算法");
        // 口径说明类问句：展示定义，不生成新口径（G5）
        this.definitionQuestionTerms = Set.of(
                "包含", "是否含", "口径", "怎么算", "定义", "什么意思");
        // 滚动窗口与上一自然月冲突，须反问或显式标注所选口径
        this.rollingTimeTerms = Set.of(
                "最近 30 天", "最近30天", "近 30 天", "近30天", "30 天", "30天", "滚动 30 天");
    }

    public static SemanticRegistry standard() {
        return STANDARD;
    }

    private static Map<String, SemanticTable> buildTables() {
        Map<String, SemanticTable> result = new LinkedHashMap<>();
        result.put(DIM_REGION, new SemanticTable(DIM_REGION, "地区维表", "地区下钻与行级权限切分依据", List.of(
                SemanticColumn.normal("region_id", "地区编号", "地区主键"),
                SemanticColumn.normal("region_name", "地区名称", "地区名称"),
                SemanticColumn.normal("province", "省份", "省份"),
                SemanticColumn.normal("city", "城市", "城市"),
                SemanticColumn.normal(COL_SALES_TERRITORY, "销售大区", "行级权限切分依据",
                        "大区", "区域", "片区"))));
        result.put(DIM_CUSTOMER, new SemanticTable(DIM_CUSTOMER, "客户维表", "客户主体（全部为假数据）", List.of(
                SemanticColumn.normal("customer_id", "客户编号", "客户主键"),
                SemanticColumn.normal("customer_name", "客户名称", "客户主体名称", "客户", "买家", "会员"),
                SemanticColumn.normal("region_id", "地区编号", "关联地区维表"),
                SemanticColumn.normal("customer_level", "客户等级", "客户分层", "会员等级", "等级"),
                SemanticColumn.normal("register_date", "注册日期", "注册日期"),
                SemanticColumn.sensitive("phone", "手机号", "敏感列，仅全量角色可见", "电话", "联系方式"),
                SemanticColumn.sensitive("email", "邮箱", "敏感列，仅全量角色可见", "电子邮箱"))));
        result.put(DIM_PRODUCT, new SemanticTable(DIM_PRODUCT, "商品维表", "商品与品类维表", List.of(
                SemanticColumn.normal("product_id", "商品编号", "商品主键"),
                SemanticColumn.normal("product_name", "商品名称", "商品名称", "商品", "货品"),
                SemanticColumn.normal(COL_CATEGORY, "商品品类", "一级类目", "类别", "类目", "品类", "品线"),
                SemanticColumn.normal("brand", "品牌", "品牌", "牌子"),
                SemanticColumn.normal("list_price", "标价", "商品标价", "价格", "售价"),
                SemanticColumn.sensitive("cost", "成本价", "敏感列，仅全量角色可见", "成本", "进价"))));
        result.put(FACT_ORDER, new SemanticTable(FACT_ORDER, "订单主表", "金额拆分用于制造口径歧义", List.of(
                SemanticColumn.normal("order_id", "订单编号", "订单主键", "订单号"),
                SemanticColumn.normal("customer_id", "客户编号", "关联客户维表"),
                SemanticColumn.normal("region_id", "地区编号", "关联地区维表"),
                SemanticColumn.normal(COL_ORDER_DATE, "下单日期", "时间口径基准日期，非支付日期",
                        "订单日", "日期", "时间"),
                SemanticColumn.normal(COL_ORDER_STATUS, "订单状态", "枚举 paid／refunded／cancelled",
                        "是否有效", "成交状态", "作废"),
                SemanticColumn.normal("pay_amount", "实付金额", "用户实际支付金额，含运费、未扣后续退款",
                        "销售额", "成交额", "收款额", "GMV"),
                SemanticColumn.normal("refund_amount", "退款金额", "已审批退款金额，未退款为 0",
                        "退款", "退货额", "冲销"),
                SemanticColumn.normal("freight_amount", "运费", "单独列示，不计入商品成交额",
                        "邮费", "配送费", "物流费"),
                SemanticColumn.normal("discount_amount", "优惠金额", "优惠分摊金额", "折扣", "优惠"))));
        result.put(FACT_ORDER_ITEM, new SemanticTable(FACT_ORDER_ITEM, "订单明细", "支撑品类拆解与销量口径", List.of(
                SemanticColumn.normal("item_id", "明细编号", "明细主键"),
                SemanticColumn.normal("order_id", "订单编号", "关联订单主表"),
                SemanticColumn.normal("product_id", "商品编号", "关联商品维表"),
                SemanticColumn.normal("quantity", "购买数量", "明细行件数", "件数", "销量", "数量"),
                SemanticColumn.normal("unit_price", "成交单价", "明细行单价", "单价"),
                SemanticColumn.normal("item_amount", "明细金额", "明细行金额", "金额"))));
        return Map.copyOf(result);
    }

    private static Map<String, MetricDefinition> buildMetrics() {
        Map<String, MetricDefinition> result = new LinkedHashMap<>();
        result.put(METRIC_NET_REVENUE, new MetricDefinition(
                METRIC_NET_REVENUE, "净营收",
                "已支付订单的实付金额扣除退款，不含运费",
                "SUM(pay_amount - refund_amount)", "order_status IN ('paid','refunded')",
                "元", List.of("营收", "净收入"), "v1"));
        result.put(METRIC_ORDER_COUNT, new MetricDefinition(
                METRIC_ORDER_COUNT, "订单数",
                "去重订单主表计数，含已退款单、不含取消单",
                "COUNT(DISTINCT order_id)", "order_status != 'cancelled'",
                "单", List.of("单量", "订单量"), "v1"));
        result.put(METRIC_AVG_ORDER_VALUE, new MetricDefinition(
                METRIC_AVG_ORDER_VALUE, "客单价",
                "净营收 ÷ 去重订单数（按订单，不按客户）",
                "净营收 / 订单数", "与净营收、订单数共用认证口径",
                "元/单", List.of("每单金额"), "v1"));
        result.put(METRIC_SALES_VOLUME, new MetricDefinition(
                METRIC_SALES_VOLUME, "销量",
                "明细行件数之和",
                "SUM(quantity)", "不限订单状态",
                "件", List.of("件数", "销售件数", "购买数量", "数量", "多少件"), "v1"));
        return Map.copyOf(result);
    }

    private static Map<String, DimensionDefinition> buildDimensions() {
        Map<String, DimensionDefinition> result = new LinkedHashMap<>();
        result.put(COL_SALES_TERRITORY, new DimensionDefinition(
                COL_SALES_TERRITORY, "销售大区", DIM_REGION, COL_SALES_TERRITORY,
                List.of("大区", "区域", "片区", "地区", "按地区", "按销售大区", "各销售大区")));
        result.put(COL_CATEGORY, new DimensionDefinition(
                COL_CATEGORY, "商品品类", DIM_PRODUCT, COL_CATEGORY,
                List.of("类别", "类目", "品类", "品线", "按品类", "各品类", "品类维度")));
        result.put("region_name", new DimensionDefinition(
                "region_name", "地区名称", DIM_REGION, "region_name", List.of("地区名", "按地区名称")));
        result.put("province", new DimensionDefinition(
                "province", "省份", DIM_REGION, "province", List.of("省份", "按省份")));
        result.put("brand", new DimensionDefinition(
                "brand", "品牌", DIM_PRODUCT, "brand", List.of("按品牌", "各品牌")));
        result.put("product_name", new DimensionDefinition(
                "product_name", "商品名称", DIM_PRODUCT, "product_name", List.of("按商品", "各商品")));
        result.put("customer_level", new DimensionDefinition(
                "customer_level", "客户等级", DIM_CUSTOMER, "customer_level", List.of("按客户等级")));
        result.put("order_status", new DimensionDefinition(
                "order_status", "订单状态", FACT_ORDER, COL_ORDER_STATUS, List.of("按订单状态")));
        return Map.copyOf(result);
    }

    private static List<JoinPath> buildJoins() {
        return List.of(
                new JoinPath(FACT_ORDER, DIM_REGION, "region_id", "region_id"),
                new JoinPath(FACT_ORDER, DIM_CUSTOMER, "customer_id", "customer_id"),
                new JoinPath(FACT_ORDER_ITEM, FACT_ORDER, "order_id", "order_id"),
                new JoinPath(FACT_ORDER_ITEM, DIM_PRODUCT, "product_id", "product_id"),
                new JoinPath(DIM_CUSTOMER, DIM_REGION, "region_id", "region_id"));
    }

    public Map<String, SemanticTable> tables() {
        return tables;
    }

    public SemanticTable table(String name) {
        SemanticTable table = tables.get(name);
        if (table == null) {
            throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "Schema 门",
                    "表 " + name + " 不在语义模型内", "请改用受管数据集，或先补充语义模型定义");
        }
        return table;
    }

    public List<SemanticTable> tableList() {
        return List.copyOf(tables.values());
    }

    public boolean hasField(String fieldName) {
        return tables.values().stream().anyMatch(table -> table.hasColumn(fieldName));
    }

    public boolean isSensitiveField(String fieldName) {
        return tables.values().stream()
                .anyMatch(table -> table.column(fieldName).filter(SemanticColumn::sensitive).isPresent());
    }

    public boolean hasMetric(String code) {
        return metrics.containsKey(code);
    }

    public MetricDefinition metric(String code) {
        return metrics.get(code);
    }

    public MetricDefinition requireMetric(String code) {
        MetricDefinition metric = metrics.get(code);
        if (metric == null) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "指标 " + code + " 不是受管指标", "请从指标目录中选择认证口径，或先补充指标定义");
        }
        return metric;
    }

    public List<MetricDefinition> metricList() {
        return List.copyOf(metrics.values());
    }

    /**
     * 指标版本化（`D2-06`）：审批通过或回滚后替换当前认证口径，别名索引同步重建。
     * 只允许替换已受管指标，不新增指标编码（G1）。
     */
    public synchronized void replaceMetric(MetricDefinition updated) {
        if (updated == null || updated.code() == null || !metrics.containsKey(updated.code())) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "指标不在受管目录中，无法替换口径", "请从指标目录中选择已有指标");
        }
        Map<String, MetricDefinition> next = new LinkedHashMap<>(metrics);
        next.put(updated.code(), updated);
        this.metrics = java.util.Collections.unmodifiableMap(next);
        this.aliasToMetric = buildAliasToMetric(this.metrics);
    }

    private static Map<String, String> buildAliasToMetric(Map<String, MetricDefinition> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MetricDefinition metric : source.values()) {
            result.put(metric.businessName(), metric.code());
            for (String alias : metric.aliases()) {
                result.put(alias, metric.code());
            }
        }
        return result;
    }

    /**
     * 表级描述维护（D1-04）：只改业务名与说明，不动表结构与字段清单。
     */
    public synchronized void overrideTableDescription(String tableName, String businessName,
                                                      String description) {
        SemanticTable existing = table(tableName);
        String name = businessName == null || businessName.isBlank()
                ? existing.businessName() : businessName.trim();
        Map<String, SemanticTable> next = new LinkedHashMap<>(tables);
        next.put(tableName, new SemanticTable(tableName, name,
                description == null ? existing.description() : description.trim(),
                existing.columns()));
        this.tables = java.util.Collections.unmodifiableMap(next);
    }

    /**
     * 字段描述维护（D1-04）：业务名、说明、同义词与敏感标记可编辑。
     * 若该字段被受管维度引用，维度业务名与别名索引同步刷新，保证问数检索与展示一致。
     */
    public synchronized void overrideColumnDescription(String tableName, String columnName,
                                                       String businessName, String description,
                                                       List<String> synonyms, Boolean sensitive) {
        SemanticTable existing = table(tableName);
        SemanticColumn column = existing.column(columnName).orElseThrow(() -> new QueryonException(
                ErrorCode.SCHEMA_GATE_REJECT, "元数据维护",
                "字段 " + columnName + " 不在表 " + tableName + " 的语义模型中",
                "请从字段目录中选择受管字段"));
        String name = businessName == null || businessName.isBlank()
                ? column.businessName() : businessName.trim();
        SemanticColumn updated = new SemanticColumn(columnName, name,
                description == null ? column.description() : description.trim(),
                synonyms == null ? column.synonyms() : List.copyOf(synonyms),
                sensitive == null ? column.sensitive() : sensitive);
        List<SemanticColumn> columns = existing.columns().stream()
                .map(item -> item.name().equals(columnName) ? updated : item)
                .toList();
        Map<String, SemanticTable> nextTables = new LinkedHashMap<>(tables);
        nextTables.put(tableName,
                new SemanticTable(tableName, existing.businessName(), existing.description(), columns));
        this.tables = java.util.Collections.unmodifiableMap(nextTables);

        Map<String, DimensionDefinition> nextDimensions = new LinkedHashMap<>(dimensions);
        boolean dimensionChanged = false;
        for (DimensionDefinition dimension : dimensions.values()) {
            if (dimension.table().equals(tableName) && dimension.column().equals(columnName)
                    && !dimension.businessName().equals(name)) {
                nextDimensions.put(dimension.code(), new DimensionDefinition(dimension.code(), name,
                        dimension.table(), dimension.column(), dimension.aliases()));
                dimensionChanged = true;
            }
        }
        if (dimensionChanged) {
            this.dimensions = java.util.Collections.unmodifiableMap(nextDimensions);
            this.aliasToDimension = buildAliasToDimension(this.dimensions);
        }
    }

    private static Map<String, String> buildAliasToDimension(
            Map<String, DimensionDefinition> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DimensionDefinition dimension : source.values()) {
            result.put(dimension.businessName(), dimension.code());
            for (String alias : dimension.aliases()) {
                result.put(alias, dimension.code());
            }
        }
        return result;
    }

    public DimensionDefinition dimension(String code) {
        return dimensions.get(code);
    }

    public List<DimensionDefinition> dimensionList() {
        return List.copyOf(dimensions.values());
    }

    public List<JoinPath> approvedJoins() {
        return approvedJoins;
    }

    public boolean isApprovedJoin(String fromTable, String toTable) {
        return approvedJoins.stream()
                .anyMatch(path -> path.fromTable().equals(fromTable) && path.toTable().equals(toTable));
    }

    public Set<String> ambiguousTerms() {
        return ambiguousTerms;
    }

    public Set<String> privateCaliberTerms() {
        return privateCaliberTerms;
    }

    public Set<String> definitionQuestionTerms() {
        return definitionQuestionTerms;
    }

    public Set<String> rollingTimeTerms() {
        return rollingTimeTerms;
    }

    /** 列编码 → 中文业务名：先查受管指标，再查受管维度，最后查语义表字段；未命中返回原编码。 */
    public String businessNameOf(String code) {
        MetricDefinition metric = metrics.get(code);
        if (metric != null) {
            return metric.businessName();
        }
        DimensionDefinition dimension = dimensions.get(code);
        if (dimension != null) {
            return dimension.businessName();
        }
        for (SemanticTable table : tables.values()) {
            for (SemanticColumn column : table.columns()) {
                if (column.name().equals(code)) {
                    return column.businessName();
                }
            }
        }
        return code;
    }

    /** 指标单位（如「元」「单」）；非受管指标返回空串，供报表按单位分列合计，避免跨单位混算。 */
    public String unitOf(String code) {
        MetricDefinition metric = metrics.get(code);
        return metric == null ? "" : metric.unit();
    }

    /** 维度下钻路径（当前维度编码 → 下一层更细维度编码），叶子维度不在表内。 */
    public Map<String, String> drillDown() {
        return DRILL_DOWN;
    }

    public String nextDimension(String code) {
        return DRILL_DOWN.get(code);
    }

    /** 术语库命中：指标／维度／字段／歧义词／私有口径词，候选集可查看、可定位。 */
    public SchemaLinkResult link(String question) {
        String text = question == null ? "" : question;
        Set<String> metricHits = new LinkedHashSet<>();
        Set<String> dimensionHits = new LinkedHashSet<>();
        Set<String> fieldHits = new LinkedHashSet<>();
        Set<String> matchedTerms = new LinkedHashSet<>();
        Set<String> ambiguityHits = new LinkedHashSet<>();
        Set<String> privateHits = new LinkedHashSet<>();

        for (Map.Entry<String, String> entry : aliasToMetric.entrySet()) {
            if (text.contains(entry.getKey())) {
                metricHits.add(entry.getValue());
                matchedTerms.add(entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : aliasToDimension.entrySet()) {
            if (text.contains(entry.getKey())) {
                dimensionHits.add(entry.getValue());
                matchedTerms.add(entry.getKey());
            }
        }
        for (SemanticTable table : tables.values()) {
            for (SemanticColumn column : table.columns()) {
                if (text.contains(column.businessName())) {
                    fieldHits.add(column.name());
                    matchedTerms.add(column.businessName());
                }
                for (String synonym : column.synonyms()) {
                    if (text.contains(synonym)) {
                        fieldHits.add(column.name());
                        matchedTerms.add(synonym);
                    }
                }
            }
        }
        for (String term : ambiguousTerms) {
            if (text.contains(term)) {
                ambiguityHits.add(term);
            }
        }
        for (String term : privateCaliberTerms) {
            if (text.contains(term)) {
                privateHits.add(term);
            }
        }
        for (String term : rollingTimeTerms) {
            if (text.contains(term)) {
                ambiguityHits.add(term);
            }
        }
        return new SchemaLinkResult(new ArrayList<>(metricHits), new ArrayList<>(dimensionHits),
                new ArrayList<>(fieldHits), new ArrayList<>(matchedTerms),
                new ArrayList<>(ambiguityHits), new ArrayList<>(privateHits));
    }
}
