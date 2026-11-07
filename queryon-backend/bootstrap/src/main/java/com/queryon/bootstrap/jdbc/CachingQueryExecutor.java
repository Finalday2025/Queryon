package com.queryon.bootstrap.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryon.common.observability.CacheStats;
import com.queryon.common.result.ResultSet;
import com.queryon.query.executor.QueryExecutor;
import com.queryon.semantic.plan.ExecutionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 结果缓存（D3-08）：缓存键由语义层执行计划渲染出的 SQL 派生，
 * 计划里已包含系统注入的身份过滤，因此不同身份不会命中彼此的结果。
 * Redis 不可用或序列化失败时自动降级为直查数据库，不影响主链路与治理逻辑。
 */
public final class CachingQueryExecutor implements QueryExecutor, CacheStats {

    private static final Logger log = LoggerFactory.getLogger(CachingQueryExecutor.class);
    private static final String PREFIX = "queryon:query:";

    private final QueryExecutor delegate;
    private final JdbcSqlRenderer renderer;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();

    public CachingQueryExecutor(QueryExecutor delegate, JdbcSqlRenderer renderer,
                                StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this.delegate = delegate;
        this.renderer = renderer;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public ResultSet execute(ExecutionPlan plan) {
        String key = PREFIX + digest(renderer.render(plan));
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                hits.incrementAndGet();
                log.debug("Redis 缓存命中：{}", key);
                return objectMapper.readValue(cached, ResultSet.class);
            }
            misses.incrementAndGet();
        } catch (Exception e) {
            fallbacks.incrementAndGet();
            log.warn("Redis 缓存读取失败，降级直查数据库：{}", e.getMessage());
        }
        ResultSet result = delegate.execute(plan);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), ttl);
            writes.incrementAndGet();
            log.debug("Redis 缓存已写入：{}", key);
        } catch (Exception e) {
            fallbacks.incrementAndGet();
            log.warn("Redis 缓存写入失败，结果仍正常返回：{}", e.getMessage());
        }
        return result;
    }

    @Override
    public long estimateRowCount(String table) {
        return delegate.estimateRowCount(table);
    }

    @Override
    public String renderedSql(ExecutionPlan plan) {
        return delegate.renderedSql(plan);
    }

    @Override
    public Snapshot snapshot() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        return new Snapshot(true, hit, miss, writes.get(), fallbacks.get(),
                total == 0 ? 0.0 : (double) hit / total, ttl.toSeconds());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
