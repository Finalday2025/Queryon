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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 逻辑数据集（`D1-05`）回归：只引用受管对象、默认过滤与权限随人、裁剪范围外一律拒绝。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class LogicalDatasetTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String ADMIN = "admin_bi";
    private static final String ANALYST = "analyst_east";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 数据集可裁剪共享并按提问者身份执行默认过滤() throws Exception {
        String body = "{\"name\":\"华东订单分析\",\"description\":\"回归用\","
                + "\"metrics\":[\"net_revenue\",\"order_count\"],"
                + "\"dimensions\":[\"sales_territory\"],"
                + "\"joins\":[\"fact_order->dim_region\"],\"shared\":false,"
                + "\"defaultFilters\":{\"sales_territory\":\"华东\"}}";
        String created = mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ANALYST))
                .andExpect(jsonPath("$.metrics.length()").value(2))
                .andExpect(jsonPath("$.defaultFilters.sales_territory").value("华东"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();

        mockMvc.perform(get("/api/logical-datasets").header(USER_HEADER, ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        // 指标缺省时使用数据集内全部指标；默认过滤由服务端注入。
        String queried = mockMvc.perform(post("/api/logical-datasets/" + id + "/query")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[\"sales_territory\"],\"timeWindow\":\"ALL\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.rows").isArray())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = mapper.readTree(queried).path("resultSet").path("rows");
        assertTrue(rows.size() > 0, "按数据集取数应返回结果");
        for (JsonNode row : rows) {
            assertEquals("华东", row.path("sales_territory").asText(),
                    "默认过滤应对所有行生效，且不因身份变化而失效");
        }

        // 共享后仍按提问者身份执行：分析员带行级华东过滤，结果不越界。
        mockMvc.perform(put("/api/logical-datasets/" + id)
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true));

        mockMvc.perform(post("/api/logical-datasets/" + id + "/query")
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[\"sales_territory\"],\"timeWindow\":\"ALL\",\"limit\":10}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/logical-datasets/" + id).header(USER_HEADER, ANALYST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("已删除"));
    }

    @Test
    void 裁剪范围外的指标维度关联与过滤一律拒绝() throws Exception {
        mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"无指标\",\"metrics\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1001"));

        mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"幻觉指标\",\"metrics\":[\"gmv\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1004"));

        mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"幻觉维度\",\"metrics\":[\"net_revenue\"],"
                                + "\"dimensions\":[\"not_a_dimension\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1003"));

        mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"未批准关联\",\"metrics\":[\"net_revenue\"],"
                                + "\"joins\":[\"fact_order->dim_product\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1003"));

        mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越界过滤\",\"metrics\":[\"net_revenue\"],"
                                + "\"dimensions\":[\"sales_territory\"],"
                                + "\"defaultFilters\":{\"category\":\"家电\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1004"));

        String created = mockMvc.perform(post("/api/logical-datasets")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"范围校验\",\"metrics\":[\"net_revenue\"],"
                                + "\"dimensions\":[\"sales_territory\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();

        mockMvc.perform(post("/api/logical-datasets/" + id + "/query")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[\"category\"],\"timeWindow\":\"ALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1004"));

        mockMvc.perform(post("/api/logical-datasets/" + id + "/query")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"sales_volume\"],\"timeWindow\":\"ALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1004"));
    }
}
