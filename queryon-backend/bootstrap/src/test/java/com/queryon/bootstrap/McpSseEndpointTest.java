package com.queryon.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** MCP SSE 端点冒烟测试：真实 Tomcat 端口下 GET /sse 应返回 200 且为事件流。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSseEndpointTest {

    @LocalServerPort
    private int port;

    @Test
    void sse端点返回200且为事件流() throws Exception {
        URL url = new URL("http://localhost:" + port + "/sse");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        int code = conn.getResponseCode();
        assertEquals(HttpStatus.OK.value(), code, "/sse 应返回 200");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String firstLine = reader.readLine();
            assertNotNull(firstLine, "/sse 应返回 SSE 流内容（首行）");
        }
        conn.disconnect();
    }
}
