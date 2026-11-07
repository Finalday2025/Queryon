package com.queryon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M2 社区版剩余能力回归：按方言翻译、数据预览与值画像、可观测性与结论溯源。 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class M2CapabilityTest {

    private static final String USER_HEADER = "X-Queryon-User";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 语义翻译按方言返回且带指标版本() throws Exception {
        String json = mockMvc.perform(post("/api/semantic/translate")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"sales_territory\"],"
                                + "\"timeWindow\":\"ALL\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dialect").value("PG"))
                .andExpect(jsonPath("$[1].dialect").value("MYSQL"))
                .andExpect(jsonPath("$[0].metricVersions[0]").value("net_revenue@v1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode translations = mapper.readTree(json);
        assertNotEquals(translations.get(0).path("sql").asText(),
                translations.get(1).path("sql").asText(), "两种方言快照应不同");

        mockMvc.perform(get("/api/semantic/dialects").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("PG"))
                .andExpect(jsonPath("$[1]").value("MYSQL"));
    }

    @Test
    void 数据画像只暴露受管字段且含值域信息() throws Exception {
        mockMvc.perform(get("/api/semantic/profile")
                        .param("table", "fact_order")
                        .param("column", "sales_territory")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("销售大区"))
                .andExpect(jsonPath("$.topValues").isArray())
                .andExpect(jsonPath("$.nullRate").exists())
                .andExpect(jsonPath("$.sample").isArray());

        mockMvc.perform(get("/api/semantic/profile")
                        .param("table", "fact_order")
                        .param("column", "not_a_managed_field")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1003"));
    }

    @Test
    void 可观测统计覆盖成功与拦截且链路可溯源() throws Exception {
        String ok = mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"ALL\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String traceId = mapper.readTree(ok).path("traceId").asText();

        mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"not_a_dimension\"],"
                                + "\"timeWindow\":\"ALL\"}"))
                .andExpect(status().isBadRequest());

        String metrics = mockMvc.perform(get("/api/observability/metrics")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.recent").isArray())
                .andReturn().getResponse().getContentAsString();
        JsonNode snapshot = mapper.readTree(metrics);
        assertTrue(snapshot.path("succeeded").asLong() >= 1, "应记录成功取数");
        assertTrue(snapshot.path("rejected").asLong() >= 1, "应记录护栏拦截");
        assertTrue(snapshot.path("byError").has("QON-1003"), "应按错误码统计护栏拦截");

        mockMvc.perform(get("/api/observability/traces/" + traceId)
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.stages").isArray())
                .andExpect(jsonPath("$.success").value(true));
    }
}
