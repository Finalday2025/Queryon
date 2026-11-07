package com.queryon.bootstrap.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryon.bootstrap.QueryonApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M2 出口标准「跨入口口径一致」回归：把 MCP 工具入口并入 REST 已有的口径一致性断言。
 * 通过 ToolCallback bean 调用，覆盖 MCP Sync 工具执行的真实路径（input schema 解析 + 方法调用），
 * 并验证 MCP 通道同样执行权限随人（G4）、不旁路治理横切。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class McpCrossEntryConsistencyTest {

    private static final String USER_HEADER = "X-Queryon-User";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Map<String, ToolCallback> callbacks;

    private ToolCallback tool(String name) {
        return callbacks.values().stream()
                .filter(callback -> name.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 MCP 工具：" + name));
    }

    private String restAsk(String question, String userId, String sessionId) throws Exception {
        return mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\",\"sessionId\":\"" + sessionId + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String restQuery(String userId) throws Exception {
        return mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"LAST_MONTH\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private double netRevenue(String json) throws Exception {
        return mapper.readTree(json).path("resultSet").path("rows").get(0).path("net_revenue").asDouble();
    }

    @Test
    void MCP工具名由方法名派生且两个工具都已注册() {
        assertTrue(callbacks.values().stream().anyMatch(cb -> "ask".equals(cb.getToolDefinition().name())),
                "应注册问数工具 ask");
        assertTrue(callbacks.values().stream().anyMatch(cb -> "query".equals(cb.getToolDefinition().name())),
                "应注册统一查询工具 query");
        assertEquals(2, callbacks.size(), "MCP 工具数量应为 2");
    }

    @Test
    void MCP问数工具与REST问数口径一致() throws Exception {
        String mcp = tool("ask").call("{\"question\":\"上月净营收是多少\",\"userId\":\"admin_bi\"}");
        String rest = restAsk("上月净营收是多少", "admin_bi", "it-mcp-cross-ask");
        assertEquals(netRevenue(rest), netRevenue(mcp), 0.0001,
                "MCP ask 与 REST /api/ask 对同一指标同一时间窗口径应一致");
    }

    @Test
    void MCP统一查询工具与REST统一查询口径一致() throws Exception {
        String mcp = tool("query").call(
                "{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"LAST_MONTH\",\"userId\":\"admin_bi\"}");
        String rest = restQuery("admin_bi");
        assertEquals(netRevenue(rest), netRevenue(mcp), 0.0001,
                "MCP query 与 REST /api/queries 对同一语义查询口径应一致");
    }

    @Test
    void MCP通道权限随人不旁路() throws Exception {
        String question = "按销售大区看上月的净营收";
        JsonNode adminMcp = mapper.readTree(tool("ask").call(
                "{\"question\":\"" + question + "\",\"userId\":\"admin_bi\"}"));
        JsonNode analystMcp = mapper.readTree(tool("ask").call(
                "{\"question\":\"" + question + "\",\"userId\":\"analyst_east\"}"));

        for (String userId : List.of("admin_bi", "analyst_east")) {
            JsonNode mcp = "admin_bi".equals(userId) ? adminMcp : analystMcp;
            JsonNode rest = mapper.readTree(restAsk(question, userId, "it-mcp-cross-" + userId));
            assertEquals(rest.path("status").asText(), mcp.path("status").asText(),
                    userId + " 在 MCP 与 REST 的状态应一致");
            assertEquals(rest.path("resultSet"), mcp.path("resultSet"),
                    userId + " 在 MCP 与 REST 的结果集应完全一致");
        }

        int adminRows = adminMcp.path("resultSet").path("rows").size();
        int analystRows = analystMcp.path("resultSet").path("rows").size();
        assertTrue(analystRows <= adminRows && adminRows > 0,
                "analyst_east 行级权限应收窄结果集，实际 admin=" + adminRows + " analyst=" + analystRows);
    }
}
