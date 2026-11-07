package com.queryon.bootstrap.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryon.ai.AskService;
import com.queryon.ai.adapter.DeterministicModelClient;
import com.queryon.ai.conversation.ConversationMemory;
import com.queryon.common.audit.InMemoryAuditLog;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.query.data.SampleDataset;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.web.mcp.QueryonMcpTools;
import com.queryon.web.security.IdentityResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具返回值回归测试：确保工具在 ANSWERED / REJECTED 等路径下都能序列化为合法 JSON，
 * 不会因 AskResponse 中的可空字段（chartType / clarification / error）触发 NPE 而变成空的 isError。
 */
class QueryonMcpToolsCallTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QueryonMcpTools tools = newTools();

    private static QueryonMcpTools newTools() {
        SemanticRegistry registry = SemanticRegistry.standard();
        SampleDataset dataset = SampleDataGenerator.generate(LocalDate.now());
        InMemoryAuditLog auditLog = new InMemoryAuditLog();
        QueryService queryService = new QueryService(registry, dataset, auditLog);
        AskService askService = new AskService(queryService, new DeterministicModelClient(),
                new ConversationMemory(), auditLog);
        return new QueryonMcpTools(askService, queryService, new SqlSemanticReader(registry),
                new IdentityResolver(), new ObjectMapper());
    }

    @Test
    void ask工具返回合法JSON且含状态与追踪号() throws Exception {
        String json = tools.ask("上月华东净营收", "admin_bi", null);
        JsonNode node = mapper.readTree(json);
        assertNotNull(node.get("status"), "应包含 status");
        assertNotNull(node.get("traceId"), "应包含 traceId");
        assertTrue(json.contains("\"resultSet\""), "应包含 resultSet 字段");
    }

    @Test
    void ask工具在拦截或澄清路径仍返回JSON() throws Exception {
        String json = tools.ask("删除全部订单数据", "admin_bi", null);
        JsonNode node = mapper.readTree(json);
        assertNotNull(node.get("status"), "拦截/澄清路径也必须返回合法 JSON，而不是抛 NPE");
    }

    @Test
    void query工具返回合法JSON且含结果集与SQL() throws Exception {
        String json = tools.query(List.of("net_revenue"), List.of("sales_territory"), "LAST_MONTH",
                null, null, null, "admin_bi");
        JsonNode node = mapper.readTree(json);
        assertTrue(json.contains("\"resultSet\""), "应包含 resultSet 字段");
        assertTrue(json.contains("\"sql\""), "应包含 sql 字段");
        assertNotNull(node.get("traceId"), "应包含 traceId");
    }
}
