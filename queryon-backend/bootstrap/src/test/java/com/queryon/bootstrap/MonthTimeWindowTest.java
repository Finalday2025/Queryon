package com.queryon.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「本月」口径回归：此前解析器只认「上月／今年」，遇到「本月」不报错也不澄清，
 * 而是静默回落成「全部时间窗」，等于把用户的问题换成另一个口径。
 */
@SpringBootTest(classes = QueryonApplication.class)
@AutoConfigureMockMvc
class MonthTimeWindowTest {

    private static final String USER_HEADER = "X-Queryon-User";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 本月问数解析为MONTH_TO_DATE而不是全部时间窗() throws Exception {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        mockMvc.perform(post("/api/ask")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"本月净营收是多少\",\"sessionId\":\"mtd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.semanticQuery.timeWindow.type").value("MONTH_TO_DATE"))
                .andExpect(jsonPath("$.semanticQuery.timeWindow.start").value(firstOfMonth.toString()))
                .andExpect(jsonPath("$.semanticQuery.timeWindow.end")
                        .value(LocalDate.now().plusDays(1).toString()));
    }

    @Test
    void 统一查询入口按MONTH_TO_DATE生成同口径SQL() throws Exception {
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        mockMvc.perform(post("/api/queries")
                        .header(USER_HEADER, "admin_bi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":[\"net_revenue\"],\"timeWindow\":\"MONTH_TO_DATE\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSet.columns[0]").value("net_revenue"))
                // 统一查询响应只回 SQL 快照，时间窗体现在 order_date 的边界上。
                .andExpect(jsonPath("$.sql", containsString(firstOfMonth.toString())))
                .andExpect(jsonPath("$.sql", containsString(LocalDate.now().plusDays(1).toString())));
    }
}
