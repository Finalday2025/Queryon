package com.queryon.ai;

import com.queryon.common.result.ResultSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基础数据解读与后续推荐（社区版 D5「基础数据解读与图表说明」、D4-06／D4-09）。
 * 只对结果集里可核验的事实做确定性描述（行数、合计、极值、分组数），
 * 不推断原因、不预测趋势、不引入模型调用，因此不会超出数据所能支撑的范围。
 */
public final class ResultInterpreter {

    private ResultInterpreter() {
    }

    public static List<String> interpret(ResultSet resultSet) {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> rows = resultSet.rows();
        if (rows.isEmpty()) {
            lines.add("本次查询未返回数据，可调整时间窗或过滤条件后重试。");
            return lines;
        }
        List<String> numeric = resultSet.columns().stream()
                .filter(column -> rows.stream().allMatch(row -> row.get(column) instanceof Number))
                .toList();
        List<String> grouping = resultSet.columns().stream()
                .filter(column -> !numeric.contains(column))
                .toList();

        lines.add("本次返回 " + rows.size() + " 行"
                + (resultSet.truncated() ? "（已达行数上限，实际 " + resultSet.totalRows() + " 行）" : "") + "。");
        if (!grouping.isEmpty()) {
            lines.add("结果按 " + grouping.size() + " 个维度分组展示。");
        }
        for (String column : numeric) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0;
            String minGroup = "";
            String maxGroup = "";
            for (Map<String, Object> row : rows) {
                double value = ((Number) row.get(column)).doubleValue();
                sum += value;
                String group = grouping.isEmpty() ? ""
                        : String.valueOf(row.get(grouping.getFirst()));
                if (value < min) {
                    min = value;
                    minGroup = group;
                }
                if (value > max) {
                    max = value;
                    maxGroup = group;
                }
            }
            StringBuilder text = new StringBuilder(column).append("：合计 ").append(format(sum));
            if (!grouping.isEmpty() && rows.size() > 1) {
                text.append("，最高 ").append(format(max)).append("（").append(maxGroup)
                        .append("），最低 ").append(format(min)).append("（").append(minGroup).append("）");
            }
            lines.add(text.toString());
        }
        lines.add("口径以语义层认证指标定义为准，解读仅描述本次结果集，不代表趋势或原因判断。");
        return List.copyOf(lines);
    }

    /** 可一键继续执行的受管问句，均来自 golden eval set 覆盖的认证口径。 */
    public static List<String> suggestions() {
        return List.of("各销售大区的净营收", "各品类的订单数", "上月客单价");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
