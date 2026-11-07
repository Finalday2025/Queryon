package com.queryon.semantic.model;

import java.time.LocalDate;

/**
 * 时间口径。认证口径为「上一自然月，按 order_date 归属」（05 第 8.3 节）。
 *
 * @param type        时间窗类型
 * @param start       起始日期（含）
 * @param end         结束日期（不含）
 * @param basisColumn 归属字段，固定为 order_date
 * @param label       可展示的中文口径
 */
public record TimeWindow(Type type, LocalDate start, LocalDate end,
                         String basisColumn, String label) {

    public enum Type {
        LAST_MONTH, YEAR_TO_DATE, ALL
    }

    public static TimeWindow lastMonth(LocalDate today) {
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);
        LocalDate firstOfLastMonth = firstOfThisMonth.minusMonths(1);
        return new TimeWindow(Type.LAST_MONTH, firstOfLastMonth, firstOfThisMonth,
                "order_date", "上一自然月，按 order_date 归属");
    }

    public static TimeWindow yearToDate(LocalDate today) {
        return new TimeWindow(Type.YEAR_TO_DATE, today.withDayOfYear(1), today.plusDays(1),
                "order_date", "本年至今，按 order_date 归属");
    }

    public static TimeWindow all() {
        return new TimeWindow(Type.ALL, LocalDate.MIN, LocalDate.MAX,
                "order_date", "不限时间窗");
    }

    public boolean within(LocalDate date) {
        return date != null && !date.isBefore(start) && date.isBefore(end);
    }
}
