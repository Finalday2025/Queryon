package com.queryon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据服务 API（`D8-09`）回归：服务密钥鉴权、显式 subject 身份、权限随人、可计量且不回传密钥。
 *
 * <p>测试密钥在运行时随机生成，文件中不写入任何字面量密钥，也不代表任何真实凭据。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class DataServiceTest {

    private static final String KEY_HEADER = "X-Queryon-Service-Key";
    private static final String TEST_KEY = "test-" + UUID.randomUUID();

    @DynamicPropertySource
    static void serviceKey(DynamicPropertyRegistry registry) {
        registry.add("queryon.service.api-key", () -> TEST_KEY);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 服务密钥与主体缺一不可且结果按主体权限收敛() throws Exception {
        mockMvc.perform(post("/api/data-service/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"analyst_east\",\"metrics\":[\"net_revenue\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/data-service/query")
                        .header(KEY_HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"analyst_east\",\"metrics\":[\"net_revenue\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/data-service/query")
                        .header(KEY_HEADER, TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1001"));

        mockMvc.perform(post("/api/data-service/query")
                        .header(KEY_HEADER, TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"nobody\",\"metrics\":[\"net_revenue\"]}"))
                .andExpect(status().isUnauthorized());

        String body = mockMvc.perform(post("/api/data-service/query")
                        .header(KEY_HEADER, TEST_KEY)
                        .header("X-Queryon-Client", "regression-client")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"analyst_east\",\"metrics\":[\"net_revenue\"],"
                                + "\"dimensions\":[\"sales_territory\"],\"timeWindow\":\"ALL\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("analyst_east"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode payload = mapper.readTree(body);
        JsonNode rows = payload.path("result").path("resultSet").path("rows");
        assertTrue(rows.size() > 0, "数据服务应能取回结果");
        for (JsonNode row : rows) {
            assertEquals("华东", row.path("sales_territory").asText(),
                    "数据服务必须按 subject 身份注入行级过滤");
        }
        assertFalse(body.contains(TEST_KEY), "响应不得回传服务密钥");

        String usage = mockMvc.perform(get("/api/data-service/usage").header(KEY_HEADER, TEST_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").isNumber())
                .andReturn().getResponse().getContentAsString();
        JsonNode usageNode = mapper.readTree(usage);
        assertTrue(usageNode.path("totalCalls").asLong() >= 1);
        assertTrue(usageNode.path("bySubject").path("analyst_east").asLong() >= 1);
        assertEquals("regression-client", usageNode.path("recent").get(0).path("client").asText());
        assertFalse(usage.contains(TEST_KEY), "计量概览同样不得回传密钥");
    }

    @Test
    void 目录接口只返回受管指标与维度() throws Exception {
        String catalog = mockMvc.perform(get("/api/data-service/metrics").header(KEY_HEADER, TEST_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(catalog);
        assertTrue(node.path("metrics").size() >= 1, "目录应包含受管指标");
        assertTrue(node.path("dimensions").size() >= 4, "目录应包含受管维度");
        assertTrue(node.path("dialects").size() >= 1);
        assertTrue(catalog.contains("\"code\":\"net_revenue\""), "目录应使用受管编码而不是物理列名");
    }
}
