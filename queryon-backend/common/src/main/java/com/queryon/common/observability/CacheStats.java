package com.queryon.common.observability;

/** 查询结果缓存的可观测快照（D3-08、D8-06）；由具体执行器实现，web 层只读。 */
public interface CacheStats {

    Snapshot snapshot();

    record Snapshot(boolean enabled, long hits, long misses, long writes, long fallbacks,
                    double hitRate, long ttlSeconds) {
    }

    static CacheStats disabled() {
        return () -> new Snapshot(false, 0, 0, 0, 0, 0.0, 0);
    }
}
