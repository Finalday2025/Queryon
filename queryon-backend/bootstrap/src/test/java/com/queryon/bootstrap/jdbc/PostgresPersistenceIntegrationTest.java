package com.queryon.bootstrap.jdbc;

import com.queryon.bootstrap.QueryonApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PostgreSQL 持久化回归：真实数据库 + 已授权的初始化脚本 + 受治理问数全链路。
 *
 * <p>内存示例数据集覆盖不到的部分在这里验证：SQL 由 {@link JdbcSqlRenderer} 渲染后在真实
 * PostgreSQL 上执行、一对多明细的分摊、维表 JOIN、以及行列级权限在数据库链路上仍由系统注入。
 *
 * <p>无容器运行时（本机未安装 Docker）时整类跳过，避免离线构建与发布门禁被外部依赖阻断；
 * 跳过状态在 surefire 报告中可见，不会伪装成通过。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = QueryonApplication.class, properties = {
        "queryon.data.source=jdbc",
        // 只排除 Redis：DataSource 自动配置必须保留，连接参数由容器动态注入。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class PostgresPersistenceIntegrationTest {

    /** 容器内一次性口令，运行时随机生成，文件中不留任何字面量凭据。 */
    private static final String DB_PASSWORD = "tc-" + UUID.randomUUID().toString().replace("-", "");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("queryon_sample")
            .withUsername("queryon")
            .withPassword(DB_PASSWORD)
            .withInitScript("init/02_create_tables.sql")
            .withInitScript("init/03_sample_data.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 问数在真实数据库上按受管指标口径返回() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header("X-Queryon-User", "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上月净营收是多少\",\"sessionId\":\"pg-it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.semanticQuery.metrics[0]").value("net_revenue"))
                .andExpect(jsonPath("$.resultSet.rows", hasSize(1)))
                .andExpect(jsonPath("$.resultSet.rows[0].net_revenue").isNumber());
    }

    @Test
    void 维表JOIN与明细分摊在真实数据库上可执行() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header("X-Queryon-User", "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"按品类看净营收\",\"sessionId\":\"pg-it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.resultSet.rows[0].category").exists())
                .andExpect(jsonPath("$.resultSet.rows.length()", greaterThan(0)));
    }

    @Test
    void 行级权限在数据库链路上仍由系统注入() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header("X-Queryon-User", "analyst_east")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"按销售大区看净营收\",\"sessionId\":\"pg-it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.resultSet.rows[*].sales_territory", everyItem(is("华东"))));
    }

    @Test
    void 写操作在真实数据库上仍被护栏拒绝() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header("X-Queryon-User", "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"删除 fact_order 全部数据\",\"sessionId\":\"pg-it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.error.code").exists());
    }
}
