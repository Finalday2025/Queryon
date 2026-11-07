package com.queryon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评测延迟观测（`D6-08`）：延迟与可重复报告分离，不进入报告与门禁比较；
 * 成本维度在真实模型接入前明确标记为待启用，不伪造数值。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class EvaluationPerformanceTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String ADMIN = "admin_bi";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 延迟只作为运行期观测不进入可重复报告() throws Exception {
        String report = mockMvc.perform(post("/api/evaluations/run").header(USER_HEADER, ADMIN))
                .andReturn().getResponse().getContentAsString();
        assertFalse(report.contains("elapsedMs"), "可重复报告不得包含耗时字段");
        assertFalse(report.contains("p95"), "可重复报告不得包含分位数等运行期指标");

        String performance = mockMvc.perform(get("/api/evaluations/performance")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.performance.costStatus").value("PENDING_REAL_MODEL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(performance).path("performance");
        assertTrue(node.path("totalCases").asInt() >= 24, "至少覆盖 24 条黄金样本");
        assertTrue(node.path("averageElapsedMs").asLong() >= 0);
        assertTrue(node.path("p95ElapsedMs").asLong() >= 0);
        assertTrue(node.path("averageMsByDialect").size() >= 1);
        assertTrue(node.path("note").asText().contains("成本维度待真实模型接入"),
                "成本未接入时应显式说明待启用，而不是给出估算值");
    }
}
