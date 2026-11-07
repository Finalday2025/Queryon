package com.queryon.bootstrap.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryon.common.observability.CacheStats;
import com.queryon.query.executor.QueryExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * dev profile（queryon.data.source=jdbc）下装配数据库执行器与 Redis 缓存；
 * 默认 profile 不装配，QueryonConfiguration 自动回退内存示例数据集，
 * 因此 mvn test 与发布门禁无需任何外部服务即可重复运行。
 */
@Configuration
@ConditionalOnProperty(name = "queryon.data.source", havingValue = "jdbc")
public class JdbcDataConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataConfiguration.class);

    /** 不用 @ConditionalOnBean(JdbcTemplate.class)：自动配置 bean 的注册顺序晚于用户配置，
     *  该条件可能误判导致整个执行器没装配、静默回退内存数据源。dev 模式已配 DataSource，
     *  若 JdbcTemplate 缺失应直接让启动失败，而不是悄悄走内存。 */
    @Bean
    public QueryExecutor jdbcQueryExecutor(JdbcTemplate jdbcTemplate,
                                           ObjectProvider<StringRedisTemplate> redisProvider,
                                           ObjectMapper objectMapper,
                                           @Value("${queryon.cache.ttl-seconds:60}") long ttlSeconds) {
        log.info("数据库执行器已启用（queryon.data.source=jdbc）");
        JdbcSqlRenderer renderer = new JdbcSqlRenderer();
        QueryExecutor delegate = new JdbcQueryExecutor(jdbcTemplate, renderer);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || ttlSeconds <= 0) {
            log.warn("Redis 结果缓存未启用：redisTemplate 存在={}, TTL={}s；查询将直查数据库",
                    redis != null, ttlSeconds);
            return delegate;
        }
        try {
            String pong = redis.execute(
                    (RedisCallback<String>) connection -> connection.ping());
            log.info("Redis 结果缓存已启用：PING={}, TTL={}s, key 前缀 queryon:query:", pong, ttlSeconds);
        } catch (Exception e) {
            log.warn("Redis 连接不可用（{}）；查询仍走数据库，缓存自动降级", e.getMessage());
        }
        return new CachingQueryExecutor(delegate, renderer, redis, objectMapper,
                Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    public CacheStats cacheStats(ObjectProvider<QueryExecutor> provider) {
        QueryExecutor executor = provider.getIfAvailable();
        return executor instanceof CachingQueryExecutor cached ? cached : CacheStats.disabled();
    }
}
