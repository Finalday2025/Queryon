package com.queryon.web.controller;

import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticTable;
import com.queryon.web.metadata.MetadataSnapshotService;
import com.queryon.web.security.IdentityResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源与元数据同步（D1-01～D1-05 社区版部分）：
 * dev 模式下行数等动态元数据直查库，其余口径信息仍以语义层注册表为唯一事实源；
 * 默认模式回退内存示例数据集，保证测试与门禁离线可重复。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private static final List<String> TABLES = List.of(
            "dim_region", "dim_customer", "dim_product", "fact_order", "fact_order_item");

    private final SemanticRegistry registry;
    private final SampleDataset dataset;
    private final IdentityResolver identityResolver;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final MetadataSnapshotService snapshotService;

    public DataSourceController(SemanticRegistry registry, SampleDataset dataset,
                                IdentityResolver identityResolver,
                                ObjectProvider<JdbcTemplate> jdbcProvider,
                                MetadataSnapshotService snapshotService) {
        this.registry = registry;
        this.dataset = dataset;
        this.identityResolver = identityResolver;
        this.jdbcProvider = jdbcProvider;
        this.snapshotService = snapshotService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return List.of(summary());
    }

    /** 重新采集元数据快照并与上一份比较；返回可校验的计数与差异，不写任何数据文件。 */
    @PostMapping("/sync")
    public Map<String, Object> sync(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        MetadataSnapshotService.Capture capture = snapshotService.capture();
        Map<String, Object> result = new LinkedHashMap<>(summary());
        result.put("status", "已同步");
        result.put("snapshotId", capture.snapshot().id());
        result.put("capturedAt", capture.snapshot().capturedAt());
        result.put("diff", capture.diff());
        result.put("note", "口径信息来自语义层注册表；行数来自当前数据源；差异相对上一份快照");
        return result;
    }

    /** 元数据快照历史（D1-03）：最新在前，含每次相对上一份的差异。 */
    @GetMapping("/snapshots")
    public List<MetadataSnapshotService.Capture> snapshots(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return snapshotService.history();
    }

    private Map<String, Object> summary() {
        List<SemanticTable> tables = registry.tableList();
        int columns = tables.stream().mapToInt(table -> table.columns().size()).sum();
        int rows = countRows();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "sample-in-memory");
        item.put("name", "内置电商示例数据集");
        item.put("type", jdbcProvider.getIfAvailable() == null ? "IN_MEMORY_SAMPLE" : "POSTGRESQL");
        item.put("tables", tables.size());
        item.put("columns", columns);
        item.put("rows", rows);
        item.put("metrics", registry.metricList().size());
        item.put("dimensions", registry.dimensionList().size());
        item.put("approvedJoins", registry.approvedJoins().size());
        return item;
    }

    private int countRows() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return dataset.tableNames().stream().mapToInt(table -> dataset.rows(table).size()).sum();
        }
        int total = 0;
        for (String table : TABLES) {
            Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            total += count == null ? 0 : count.intValue();
        }
        return total;
    }
}
