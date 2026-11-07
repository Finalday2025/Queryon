package com.queryon.web.metadata;

import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.semantic.model.SemanticTable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 元数据快照与变更检测（`D1-03`）：每次采集对比上一份快照，
 * 输出新增／移除的表与字段、行数变化，并保留最近若干份快照供回溯。
 *
 * <p>快照只记录语义层受管对象与行数，不写数据文件、不记录任何业务明细。
 */
@Component
public class MetadataSnapshotService {

    public record TableSnapshot(String name, String businessName, List<String> columns, int rows) {
    }

    public record Snapshot(String id, String capturedAt, List<TableSnapshot> tables) {
    }

    public record RowChange(String table, int before, int after) {
    }

    /**
     * @param firstSnapshot 首份快照没有可比对象，差异为空
     */
    public record Diff(boolean firstSnapshot, List<String> addedTables, List<String> removedTables,
                       List<String> addedColumns, List<String> removedColumns,
                       List<RowChange> rowChanges) {
    }

    public record Capture(Snapshot snapshot, Diff diff) {
    }

    private static final int MAX_SNAPSHOTS = 20;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SemanticRegistry registry;
    private final SampleDataset dataset;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final Deque<Capture> history = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public MetadataSnapshotService(SemanticRegistry registry, SampleDataset dataset,
                                   ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.registry = registry;
        this.dataset = dataset;
        this.jdbcProvider = jdbcProvider;
    }

    /** 采集一份新快照，并与上一份比较；最近 {@value #MAX_SNAPSHOTS} 份保留在内存。 */
    public synchronized Capture capture() {
        Snapshot current = build();
        Snapshot previous = history.isEmpty() ? null : history.peekLast().snapshot();
        Diff diff = compare(previous, current);
        Capture capture = new Capture(current, diff);
        history.addLast(capture);
        while (history.size() > MAX_SNAPSHOTS) {
            history.removeFirst();
        }
        return capture;
    }

    /** 最近的快照（含各自的差异），最新在前。 */
    public synchronized List<Capture> history() {
        List<Capture> ordered = new ArrayList<>(history);
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private Snapshot build() {
        List<TableSnapshot> tables = new ArrayList<>();
        for (SemanticTable table : registry.tableList()) {
            List<String> columns = table.columns().stream().map(column -> column.name()).toList();
            tables.add(new TableSnapshot(table.name(), table.businessName(), columns,
                    countRows(table.name())));
        }
        return new Snapshot("snap-" + sequence.incrementAndGet(),
                LocalDateTime.now().format(TIME_FORMAT), List.copyOf(tables));
    }

    private Diff compare(Snapshot previous, Snapshot current) {
        if (previous == null) {
            return new Diff(true, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Map<String, TableSnapshot> beforeTables = index(previous);
        Map<String, TableSnapshot> afterTables = index(current);

        List<String> addedTables = new ArrayList<>();
        List<String> removedTables = new ArrayList<>();
        List<String> addedColumns = new ArrayList<>();
        List<String> removedColumns = new ArrayList<>();
        List<RowChange> rowChanges = new ArrayList<>();

        for (String name : afterTables.keySet()) {
            if (!beforeTables.containsKey(name)) {
                addedTables.add(name);
                continue;
            }
            TableSnapshot before = beforeTables.get(name);
            TableSnapshot after = afterTables.get(name);
            Set<String> beforeColumns = new LinkedHashSet<>(before.columns());
            Set<String> afterColumns = new LinkedHashSet<>(after.columns());
            for (String column : afterColumns) {
                if (!beforeColumns.contains(column)) {
                    addedColumns.add(name + "." + column);
                }
            }
            for (String column : beforeColumns) {
                if (!afterColumns.contains(column)) {
                    removedColumns.add(name + "." + column);
                }
            }
            if (before.rows() != after.rows()) {
                rowChanges.add(new RowChange(name, before.rows(), after.rows()));
            }
        }
        for (String name : beforeTables.keySet()) {
            if (!afterTables.containsKey(name)) {
                removedTables.add(name);
            }
        }
        return new Diff(false, List.copyOf(addedTables), List.copyOf(removedTables),
                List.copyOf(addedColumns), List.copyOf(removedColumns), List.copyOf(rowChanges));
    }

    private Map<String, TableSnapshot> index(Snapshot snapshot) {
        java.util.LinkedHashMap<String, TableSnapshot> result = new java.util.LinkedHashMap<>();
        snapshot.tables().forEach(table -> result.put(table.name(), table));
        return result;
    }

    private int countRows(String table) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return dataset.rows(table).size();
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count.intValue();
    }
}
