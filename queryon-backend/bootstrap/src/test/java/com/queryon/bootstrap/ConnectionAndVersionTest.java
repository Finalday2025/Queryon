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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 连接管理（`D1-02`）与指标版本化（`D2-06`）回归：
 * 写操作仅限全量基准身份、凭据不回传、草稿未审批不生效、回滚追加新版本并可追溯。
 *
 * <p>版本用例选用 `sales_volume`，避免改动 `net_revenue` 的版本断言。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class ConnectionAndVersionTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String ADMIN = "admin_bi";
    private static final String ANALYST = "analyst_east";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 连接管理仅管理员可维护且凭据不回传() throws Exception {
        mockMvc.perform(get("/api/connections").header(USER_HEADER, ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("conn-sample"))
                .andExpect(jsonPath("$[0].password").doesNotExist());

        String body = "{\"name\":\"测试只读连接\",\"type\":\"POSTGRESQL\",\"host\":\"127.0.0.1\","
                + "\"port\":1,\"database\":\"demo\",\"username\":\"reader\","
                + "\"password\":\"test-only\",\"readOnly\":true,\"enabled\":true}";

        mockMvc.perform(post("/api/connections")
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String created = mockMvc.perform(post("/api/connections")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();

        mockMvc.perform(post("/api/connections/" + id + "/test").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));

        mockMvc.perform(post("/api/connections/" + id + "/toggle")
                        .param("enabled", "false").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/connections")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"缺主机\",\"type\":\"POSTGRESQL\",\"port\":5432}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1001"));

        mockMvc.perform(delete("/api/connections/conn-sample").header(USER_HEADER, ADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/connections/" + id).header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已删除"));
    }

    @Test
    void 指标口径草稿未审批不生效审批与回滚均可追溯() throws Exception {
        String initial = mockMvc.perform(get("/api/semantic/metrics/sales_volume/versions")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andReturn().getResponse().getContentAsString();
        String originalDefinition = mapper.readTree(initial).get(0).path("definition").asText();

        mockMvc.perform(post("/api/semantic/metrics/sales_volume/versions")
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":\"越权提交\"}"))
                .andExpect(status().isForbidden());

        String draft = mockMvc.perform(post("/api/semantic/metrics/sales_volume/versions")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":\"明细行件数之和（口径澄清）\",\"note\":\"口径澄清\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.active").value(false))
                .andReturn().getResponse().getContentAsString();
        String draftVersion = mapper.readTree(draft).path("version").asText();

        String beforeApprove = mockMvc.perform(get("/api/semantic/metrics").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("v1", findMetric(beforeApprove, "sales_volume").path("version").asText(),
                "草稿未审批前不得替换当前口径");

        mockMvc.perform(post("/api/semantic/metrics/sales_volume/versions/" + draftVersion + "/approve")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.active").value(true));

        String approved = mockMvc.perform(get("/api/semantic/metrics").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode approvedMetric = findMetric(approved, "sales_volume");
        assertEquals(draftVersion, approvedMetric.path("version").asText(), "审批后当前版本应更新");
        assertEquals("明细行件数之和（口径澄清）", approvedMetric.path("definition").asText(),
                "审批后语义层应使用新口径");

        String rolled = mockMvc.perform(post("/api/semantic/metrics/sales_volume/versions/v1/rollback")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.note").value("回滚自 v1"))
                .andReturn().getResponse().getContentAsString();
        String rollbackVersion = mapper.readTree(rolled).path("version").asText();

        String restored = mockMvc.perform(get("/api/semantic/metrics").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode restoredMetric = findMetric(restored, "sales_volume");
        assertEquals(originalDefinition, restoredMetric.path("definition").asText(),
                "回滚后口径应恢复为初始内容");
        assertEquals(rollbackVersion, restoredMetric.path("version").asText(), "回滚应追加新版本号");

        String history = mockMvc.perform(get("/api/semantic/metrics/sales_volume/versions")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode versions = mapper.readTree(history);
        assertTrue(versions.size() >= 3, "回滚应保留完整历史，而不是覆盖旧记录");
        long activeCount = 0;
        for (JsonNode version : versions) {
            if (version.path("active").asBoolean()) {
                activeCount++;
            }
        }
        assertEquals(1, activeCount, "任一时刻只应有一个生效版本");
    }

    private JsonNode findMetric(String json, String code) throws Exception {
        for (JsonNode metric : mapper.readTree(json)) {
            if (code.equals(metric.path("code").asText())) {
                return metric;
            }
        }
        throw new AssertionError("未找到指标 " + code);
    }
}
