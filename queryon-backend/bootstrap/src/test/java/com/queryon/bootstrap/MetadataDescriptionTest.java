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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务描述维护（`D1-04`）与元数据快照差异（`D1-03`）回归：
 * 描述编辑仅限全量基准身份、展示与检索同步生效、变更可回退；同类采集之间的结构差异为空。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class MetadataDescriptionTest {

    private static final String USER_HEADER = "X-Queryon-User";
    private static final String ADMIN = "admin_bi";
    private static final String ANALYST = "analyst_east";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 字段描述可编辑可回退且仅管理员可写() throws Exception {
        mockMvc.perform(put("/api/semantic/tables/dim_region/columns/city/description")
                        .header(USER_HEADER, ANALYST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"越权改描述\"}"))
                .andExpect(status().isForbidden());

        String updated = mockMvc.perform(put("/api/semantic/tables/dim_region/columns/city/description")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"城市（回归）\",\"description\":\"回归用描述\","
                                + "\"synonyms\":[\"城市名\"],\"sensitive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("字段"))
                .andExpect(jsonPath("$.afterBusinessName").value("城市（回归）"))
                .andReturn().getResponse().getContentAsString();
        long changeId = mapper.readTree(updated).path("id").asLong();

        mockMvc.perform(get("/api/semantic/display").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnLabels.city").value("城市（回归）"));

        String changes = mockMvc.perform(get("/api/semantic/metadata/changes").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(changes.contains("城市（回归）"), "变更台账应记录 after 值");

        mockMvc.perform(post("/api/semantic/metadata/changes/" + changeId + "/revert")
                        .header(USER_HEADER, ADMIN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/semantic/display").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnLabels.city").value("城市"));

        mockMvc.perform(put("/api/semantic/tables/not_a_table/description")
                        .header(USER_HEADER, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"幻觉表\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QON-1003"));
    }

    @Test
    void 连续采集快照时结构差异为空且可查历史() throws Exception {
        String first = mockMvc.perform(post("/api/datasources/sync").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").exists())
                .andReturn().getResponse().getContentAsString();
        assertTrue(mapper.readTree(first).path("diff").has("firstSnapshot"));

        String second = mockMvc.perform(post("/api/datasources/sync").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff.firstSnapshot").value(false))
                .andExpect(jsonPath("$.diff.addedTables").isEmpty())
                .andExpect(jsonPath("$.diff.removedTables").isEmpty())
                .andExpect(jsonPath("$.diff.addedColumns").isEmpty())
                .andExpect(jsonPath("$.diff.removedColumns").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String snapshotId = mapper.readTree(second).path("snapshotId").asText();

        String history = mockMvc.perform(get("/api/datasources/snapshots").header(USER_HEADER, ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode snapshots = mapper.readTree(history);
        assertTrue(snapshots.size() >= 2, "应保留多次采集的快照");
        assertEquals(snapshotId, snapshots.get(0).path("snapshot").path("id").asText(),
                "历史应按最新在前返回");
        assertFalse(snapshots.get(0).path("diff").path("firstSnapshot").asBoolean());
    }
}
