package com.queryon.evaluation;

import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.SemanticTranslator;
import com.queryon.semantic.SqlDialect;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.semantic.model.TimeWindow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按数据库方言分组的语义查询翻译回归（D6-08、M2 出口标准）：
 * 同一语义查询在各方言下指标口径、过滤与行数上限必须语义等价，仅标识符引用不同；
 * PG 快照与历史完全一致，避免翻译改造引入口径回归。24 条 golden set 的方言标签仍为 PG，
 * 按 05 第 8.5 节定稿不变，本回归是独立补充。
 */
class SemanticDialectRegressionTest {

    private final SemanticRegistry registry = SemanticRegistry.standard();
    private final SemanticTranslator translator = new SemanticTranslator(registry);

    private SemanticQuery query() {
        return SemanticQuery.empty()
                .withMetrics(List.of("net_revenue", "order_count"))
                .withDimensions(List.of("sales_territory"))
                .withTimeWindow(TimeWindow.all())
                .withFilter("sales_territory", "华东")
                .withLimit(10);
    }

    @Test
    void PG方言与历史翻译快照完全一致() {
        assertEquals(translator.translate(query()).sql(),
                translator.translate(query(), SqlDialect.PG).sql(),
                "PG 为默认方言，翻译输出不得发生变化");
    }

    @Test
    void 按方言分组翻译语义等价且方言特征可区分() {
        String pg = translator.translate(query(), SqlDialect.PG).sql();
        String mysql = translator.translate(query(), SqlDialect.MYSQL).sql();
        assertNotEquals(pg, mysql, "两种方言的 SQL 快照应可区分");
        assertTrue(mysql.contains("`net_revenue`"), "MYSQL 应引用指标标识符");
        assertTrue(mysql.contains("`sales_territory`"), "MYSQL 应引用维度标识符");
        assertFalse(pg.contains("`"), "PG 快照保持无反引号");

        for (String fragment : List.of(
                "SUM(CASE WHEN order_status IN ('paid','refunded') THEN pay_amount - refund_amount ELSE 0 END)",
                "COUNT(DISTINCT CASE WHEN order_status <> 'cancelled' THEN order_id END)",
                "'华东'",
                "LIMIT 10")) {
            assertTrue(pg.contains(fragment), "PG 缺少语义片段：" + fragment);
            assertTrue(mysql.contains(fragment), "MYSQL 缺少语义片段：" + fragment);
        }
    }

    @Test
    void 两种方言分组全部通过语义约束且结果可重复() {
        Map<String, String> byDialect = new LinkedHashMap<>();
        for (SqlDialect dialect : SqlDialect.values()) {
            byDialect.put(dialect.name(), translator.translate(query(), dialect).sql());
        }
        assertEquals(2, byDialect.size(), "按方言分组不得缺失分组");
        byDialect.values().forEach(sql -> assertTrue(sql.contains("LIMIT 10")));
        assertEquals(byDialect.get("MYSQL"),
                translator.translate(query(), SqlDialect.MYSQL).sql(),
                "翻译结果应确定可重复，不含时间戳或随机量");
    }

    @Test
    void 翻译可关联到认证指标版本() {
        assertEquals("v1", registry.requireMetric("net_revenue").version(),
                "语义翻译应能关联到版本化的认证口径");
    }
}
