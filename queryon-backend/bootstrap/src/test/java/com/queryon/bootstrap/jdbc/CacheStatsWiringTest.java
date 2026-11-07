package com.queryon.bootstrap.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryon.common.observability.CacheStats;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * jdbc + Redis 装配下的可观测接线回归。
 *
 * <p>{@link CachingQueryExecutor} 同时实现 QueryExecutor 与 CacheStats，容器里因此有两个类型匹配的
 * bean；若不显式指定优先项，`/api/observability/cache` 会在注入时抛
 * {@code NoUniqueBeanDefinitionException}。该缺陷只在 jdbc profile 下出现，内存数据集的
 * 回归测试与 golden set 都覆盖不到，因此单列一条装配回归。
 */
class CacheStatsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JdbcDataConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("queryon.data.source=jdbc", "queryon.cache.ttl-seconds=60");

    @Test
    void 缓存统计视图在存在缓存执行器时仍可唯一注入() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(CacheStats.class))
                    .as("执行器自身也实现了 CacheStats，容器中应有两个候选 bean")
                    .hasSize(2);
            CacheStats stats = context.getBean(CacheStats.class);
            assertThat(stats.snapshot().enabled())
                    .as("注入的应是启用了缓存的那份统计视图")
                    .isTrue();
        });
    }
}
