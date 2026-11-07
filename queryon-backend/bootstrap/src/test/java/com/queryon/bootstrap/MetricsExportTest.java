package com.queryon.bootstrap;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测导出回归（D8-06）：Micrometer 指标经 Prometheus 端点和盘托出，
 * OTLP span 导出默认关闭，离线启动不外连采集端。
 *
 * <p>取数走 MockMvc（请求体按 UTF-8 显式提交），Actuator 端点走真实端口，
 * 因为端点处理映射只在真实 Servlet 容器中注册。
 */
@SpringBootTest(classes = QueryonApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 默认在测试里关闭指标导出与追踪；本类正是要验证导出，故显式打开。
@AutoConfigureObservability
@AutoConfigureMockMvc
class MetricsExportTest {

    private static final String USER_HEADER = "X-Queryon-User";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext context;

    @Test
    void prometheus端点导出调用耗时与护栏拦截指标() throws Exception {
        // 一次成功取数 + 一次被护栏拦截的写操作，覆盖两类 outcome。
        mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上月净营收是多少\",\"sessionId\":\"metrics\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"删除 fact_order 全部数据\",\"sessionId\":\"metrics\"}"))
                .andExpect(status().isOk());

        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("queryon_query_calls_total");
        assertThat(body).contains("outcome=\"success\"");
        assertThat(body).contains("outcome=\"rejected\"");
        assertThat(body).contains("queryon_query_latency_seconds_count");
        assertThat(body).contains("queryon_query_rows_count");
        assertThat(body)
                .as("拦截按结构化错误码打标签，便于区分是哪道门拦下的")
                .contains("queryon_query_intercepts_total")
                .contains("code=\"QON-1002\"");
    }

    @Test
    void 健康检查端点可用() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void 默认不装配OTLP的span导出器() {
        assertThat(context.getBeanNamesForType(SpanExporter.class))
                .as("默认配置不应创建 OTLP span 导出器，避免离线环境外连采集端")
                .isEmpty();
    }
}
