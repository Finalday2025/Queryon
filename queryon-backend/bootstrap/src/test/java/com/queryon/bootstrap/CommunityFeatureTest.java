package com.queryon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 社区版补齐能力回归：仪表板跨入口口径一致、审计台账、订阅、数据源同步、解读与鉴权一致性。 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class CommunityFeatureTest {

    private static final String USER_HEADER = "X-Queryon-User";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 仪表板与统一查询在同一指标同一时间窗结果一致() throws Exception {
        String dashboard = mockMvc.perform(get("/api/dashboard")
                        .param("timeWindow", "LAST_MONTH")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        JsonNode cards = mapper.readTree(dashboard).path("cards");

        String total = mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"LAST_MONTH\"}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(total).path("resultSet"),
                cards.get(0).path("resultSet"),
                "仪表板单值卡片与统一查询入口口径应一致（02 第 6.3 节判据 2）");

        String byRegion = mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"sales_territory\"],"
                                + "\"timeWindow\":\"LAST_MONTH\",\"limit\":50}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(byRegion).path("resultSet"),
                cards.get(1).path("resultSet"),
                "仪表板分组卡片与统一查询入口结果集应完全一致");
    }

    @Test
    void 审计台账可查询且记录护栏拦截() throws Exception {
        mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"not_a_dimension\"],"
                                + "\"timeWindow\":\"ALL\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/audit/events")
                        .param("outcome", "拦截")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("拦截"))
                .andExpect(jsonPath("$[0].traceId").exists());
    }

    @Test
    void 订阅按身份隔离且立即执行留痕() throws Exception {
        String saved = mockMvc.perform(post("/api/subscriptions")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"大区月报\",\"frequency\":\"每月\","
                                + "\"query\":{\"metrics\":[\"net_revenue\"],"
                                + "\"dimensions\":[\"sales_territory\"],\"timeWindow\":\"LAST_MONTH\"}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(saved).path("id").asText();

        mockMvc.perform(post("/api/subscriptions/" + id + "/run").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.rows").isArray());

        String list = mockMvc.perform(get("/api/subscriptions").header(USER_HEADER, "admin_bi"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(list).get(0).path("runCount").asInt());

        mockMvc.perform(post("/api/subscriptions/" + id + "/run").header(USER_HEADER, "analyst_east"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 数据源与元数据同步只暴露内置示例数据源() throws Exception {
        mockMvc.perform(get("/api/datasources").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("IN_MEMORY_SAMPLE"))
                .andExpect(jsonPath("$[0].metrics").value(4));
        mockMvc.perform(post("/api/datasources/sync").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已同步"));
    }

    @Test
    void 问数结果含确定性解读与后续推荐() throws Exception {
        String json = mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"各销售大区的净营收\",\"sessionId\":\"it-insight\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(json);
        assertTrue(node.path("interpretation").isArray() && !node.path("interpretation").isEmpty());
        assertFalse(node.path("suggestions").isEmpty());
    }

    @Test
    void 语义目录端点统一要求身份() throws Exception {
        mockMvc.perform(get("/api/semantic/metrics"))
                .andExpect(status().isUnauthorized());
    }
}
