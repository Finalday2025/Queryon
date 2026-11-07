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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 社区版补齐能力回归：异步长查询、分享链接、订阅调度、缓存可观测（D3-07／D3-08）。 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class CommunityCapabilityTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String QUERY = "{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"ALL\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 异步查询可提交轮询且按身份隔离() throws Exception {
        String submitted = mockMvc.perform(post("/api/queries/async")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON).content(QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(submitted).path("id").asText();

        String status = "";
        String last = "";
        for (int i = 0; i < 50; i++) {
            last = mockMvc.perform(get("/api/queries/async/" + id).header(USER_HEADER, "admin_bi"))
                    .andReturn().getResponse().getContentAsString();
            status = mapper.readTree(last).path("status").asText();
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals("DONE", status, "异步任务应完成而非失败");
        assertFalse(mapper.readTree(last).path("result").path("resultSet").path("rows").isEmpty(),
                "完成后应返回结果集");

        mockMvc.perform(get("/api/queries/async/" + id).header(USER_HEADER, "analyst_east"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 分享链接按创建者身份复用且跨身份被拒() throws Exception {
        String saved = mockMvc.perform(post("/api/shares")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"大区月报\",\"query\":" + QUERY + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(saved).path("token").asText();

        mockMvc.perform(get("/api/shares/" + token).header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.rows").isArray());
        mockMvc.perform(get("/api/shares/" + token).header(USER_HEADER, "analyst_east"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/shares/" + token).header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已撤销"));
    }

    @Test
    void 订阅周期调度可注册与取消且手动执行仍走统一入口() throws Exception {
        String saved = mockMvc.perform(post("/api/subscriptions")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"每日汇总\",\"frequency\":\"每日\","
                                + "\"query\":" + QUERY + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(saved).path("id").asText();

        mockMvc.perform(post("/api/subscriptions/" + id + "/schedule")
                        .param("intervalSeconds", "60").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(60));
        mockMvc.perform(get("/api/subscriptions/schedules").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + id).exists());
        mockMvc.perform(delete("/api/subscriptions/" + id + "/schedule")
                        .header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(0));

        mockMvc.perform(post("/api/subscriptions/" + id + "/run").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.rows").isArray());
    }

    @Test
    void 缓存统计端点在离线模式下可用() throws Exception {
        String json = mockMvc.perform(get("/api/observability/cache").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(json);
        assertTrue(node.has("hitRate"));
        assertFalse(node.path("enabled").asBoolean(), "测试走内存执行器，缓存应为未启用");
    }
}
