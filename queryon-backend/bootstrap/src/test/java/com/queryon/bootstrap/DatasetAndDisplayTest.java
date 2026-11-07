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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 简单报表／交叉表支撑能力回归：展示元数据、数据集级复用与身份隔离。
 * 复用统一查询入口，结果集与直接查询一致；跨身份读取数据集一律拒绝，治理横切不旁路。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class DatasetAndDisplayTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String QUERY_BODY =
            "{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"sales_territory\"],"
                    + "\"timeWindow\":\"LAST_MONTH\",\"limit\":10}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 展示元数据提供中文列名单位与下钻路径() throws Exception {
        mockMvc.perform(get("/api/semantic/display").header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnLabels.net_revenue").value("净营收"))
                .andExpect(jsonPath("$.columnLabels.sales_territory").value("销售大区"))
                .andExpect(jsonPath("$.metricUnits.net_revenue").value("元"))
                .andExpect(jsonPath("$.drillDown.sales_territory").value("province"))
                .andExpect(jsonPath("$.drillDown.category").value("brand"));

        // 下钻链路：按点击值过滤并切到下一层维度，仍走同一统一查询入口与护栏
        mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"dimensions\":[\"province\"],"
                                + "\"timeWindow\":\"LAST_MONTH\","
                                + "\"filters\":{\"sales_territory\":\"华东\"},\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.columns[0]").value("province"))
                .andExpect(jsonPath("$.resultSet.rows[0].net_revenue").exists());
    }

    @Test
    void 数据集复用同一结果且读取不重新查询() throws Exception {
        String direct = mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUERY_BODY))
                .andReturn().getResponse().getContentAsString();

        String saved = mockMvc.perform(post("/api/datasets")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"大区净营收\",\"query\":" + QUERY_BODY + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(saved).path("id").asText();

        String reused = mockMvc.perform(get("/api/datasets/" + id).header(USER_HEADER, "admin_bi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reusedAgain = mockMvc.perform(get("/api/datasets/" + id).header(USER_HEADER, "admin_bi"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(mapper.readTree(direct).path("resultSet"),
                mapper.readTree(reused).path("resultSet"),
                "数据集结果集应与直接走统一查询入口完全一致");
        assertEquals(reused, reusedAgain, "同一数据集重复读取应返回同一份缓存结果，未重新查询");
    }

    @Test
    void 数据集按提问者身份隔离且跨身份读取被拒() throws Exception {
        String saved = mockMvc.perform(post("/api/datasets")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"全量大区\",\"query\":" + QUERY_BODY + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(saved).path("id").asText();

        String eastList = mockMvc.perform(get("/api/datasets").header(USER_HEADER, "analyst_east"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(eastList.contains(id), "数据集列表不应暴露其它身份的数据集");

        String adminList = mockMvc.perform(get("/api/datasets").header(USER_HEADER, "admin_bi"))
                .andReturn().getResponse().getContentAsString();
        JsonNode adminNode = mapper.readTree(adminList);
        assertEquals(id, adminNode.get(adminNode.size() - 1).path("id").asText(),
                "创建者应能看到自己保存的数据集");

        mockMvc.perform(get("/api/datasets/" + id).header(USER_HEADER, "analyst_east"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QON-3001"));
    }
}
