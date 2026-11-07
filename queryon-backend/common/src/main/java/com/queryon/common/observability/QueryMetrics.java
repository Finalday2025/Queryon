package com.queryon.common.observability;

/**
 * 受治理查询的指标出口（D8-06）：把唯一编排入口的调用结果转成外部可采集的指标。
 *
 * <p>接口放在 common，具体实现（Micrometer）留在装配层，使 query 模块不依赖监控库；
 * 业务模块保持纯 Java，替换或关闭导出器都不影响治理逻辑。
 */
public interface QueryMetrics {

    /**
     * @param durationMs 本次调用耗时（毫秒）
     * @param success    是否成功；失败包含四道护栏的拦截
     * @param errorCode  失败时的结构化错误码，成功时为 null
     * @param rows       返回行数，失败时为 0
     */
    void record(long durationMs, boolean success, String errorCode, int rows);

    /** 未装配监控组件时的空实现，保证纯单元测试零依赖。 */
    QueryMetrics NOOP = (durationMs, success, errorCode, rows) -> { };
}
