package com.queryon.common.context;

import java.util.Map;
import java.util.Set;

/**
 * 提问者身份与权限上下文。权限过滤由系统在语义查询层注入，不进入生成侧提示词。
 *
 * @param userId          内置账号标识
 * @param role            角色名，取值见 05 第 8.4 节
 * @param allowedTables   可读数据集
 * @param rowFilters      行级过滤（列名 → 等值），系统注入
 * @param deniedColumns   禁止返回的列
 * @param maskedColumns   需掩码返回的列
 */
public record Actor(String userId, String role, Set<String> allowedTables,
                    Map<String, String> rowFilters, Set<String> deniedColumns,
                    Set<String> maskedColumns) {

    public static final String ROLE_ADMIN_BI = "admin_bi";
    public static final String ROLE_ANALYST_EAST = "analyst_east";

    public Actor {
        allowedTables = Set.copyOf(allowedTables);
        rowFilters = Map.copyOf(rowFilters);
        deniedColumns = Set.copyOf(deniedColumns);
        maskedColumns = Set.copyOf(maskedColumns);
    }

    /** 全量基准身份（05 第 8.4 节）。 */
    public static Actor adminBi() {
        return new Actor("admin_bi", ROLE_ADMIN_BI,
                Set.of("dim_region", "dim_customer", "dim_product", "fact_order", "fact_order_item"),
                Map.of(), Set.of(), Set.of());
    }

    /** 华东分析员：行级华东、屏蔽成本与联系方式、客户名称掩码（05 第 8.4 节）。 */
    public static Actor analystEast() {
        return new Actor("analyst_east", ROLE_ANALYST_EAST,
                Set.of("dim_region", "dim_customer", "dim_product", "fact_order", "fact_order_item"),
                Map.of("sales_territory", "华东"),
                Set.of("cost", "phone", "email", "毛利"),
                Set.of("customer_name"));
    }

    public boolean canRead(String table) {
        return allowedTables.contains(table);
    }

    public boolean isColumnDenied(String column) {
        return deniedColumns.contains(column);
    }

    public boolean isColumnMasked(String column) {
        return maskedColumns.contains(column);
    }
}
