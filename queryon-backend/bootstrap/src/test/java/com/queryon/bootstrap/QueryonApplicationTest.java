package com.queryon.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 集成测试：问数闭环、结构化拒绝、身份鉴权与评测入口。 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class QueryonApplicationTest {

    private static final String USER_HEADER = "X-Queryon-User";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void 自然语言问数返回结果与口径() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上月净营收是多少\",\"sessionId\":\"it-ask\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.resultSet.rows[0].net_revenue").exists())
                .andExpect(jsonPath("$.metricDefinitions[0]").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void 写操作诱导被结构化拒绝() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"帮我删除上个月取消的订单\",\"sessionId\":\"it-write\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.error.code").value("QON-1002"));
    }

    @Test
    void 未识别身份返回未授权结构化错误() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上月净营收是多少\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("QON-3002"));
    }

    @Test
    void 统一查询入口与问数共用认证口径() throws Exception {
        mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"LAST_MONTH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.rows[0].net_revenue").exists())
                .andExpect(jsonPath("$.metricDefinitions[0]").exists());
    }

    @Test
    void 评测入口可一键回归() throws Exception {
        mockMvc.perform(post("/api/evaluations/run").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(24))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void 口径目录可查() throws Exception {
        mockMvc.perform(get("/api/semantic/metrics").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists());
    }

    @Test
    void 问数与统一查询跨入口口径一致() throws Exception {
        String askJson = mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上月净营收是多少\",\"sessionId\":\"it-cross\"}"))
                .andReturn().getResponse().getContentAsString();
        String queryJson = mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"LAST_MONTH\"}"))
                .andReturn().getResponse().getContentAsString();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode askNode = objectMapper.readTree(askJson);
        JsonNode queryNode = objectMapper.readTree(queryJson);
        double askVal = askNode.path("resultSet").path("rows").get(0).path("net_revenue").asDouble();
        double queryVal = queryNode.path("resultSet").path("rows").get(0).path("net_revenue").asDouble();
        assertEquals(askVal, queryVal, 0.0001, "问数与统一查询对同一指标同一时间窗口径应一致");
    }

    @Test
    void MCP工具已注册为ToolCallback() {
        Map<String, ToolCallback> callbacks = context.getBeansOfType(ToolCallback.class);
        assertTrue(callbacks.size() >= 2,
                "MCP 应至少注册 2 个 ToolCallback（问数/统一查询），实际 " + callbacks.size());
    }
}
