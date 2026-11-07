package com.queryon.web.config;

import com.queryon.ai.AskService;
import com.queryon.ai.adapter.ChatModelPort;
import com.queryon.ai.adapter.DeterministicModelClient;
import com.queryon.ai.conversation.ConversationMemory;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.audit.InMemoryAuditLog;
import com.queryon.common.observability.ObservationStore;
import com.queryon.common.observability.QueryMetrics;
import com.queryon.evaluation.EvaluationRunner;
import com.queryon.evaluation.FeedbackStore;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.query.executor.InMemoryQueryExecutor;
import com.queryon.query.executor.QueryExecutor;
import org.springframework.beans.factory.ObjectProvider;
import com.queryon.query.data.SampleDataGenerator;
import com.queryon.query.data.SampleDataset;
import com.queryon.query.profile.DataProfileService;
import com.queryon.semantic.MetricVersionStore;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.web.security.IdentityResolver;
import com.queryon.web.service.DashboardService;
import com.queryon.web.mcp.QueryonMcpTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 手动装配各层能力契约。业务模块保持纯 Java，AI 只依赖语义层与查询层的契约；
 * 后续接入持久化、Spring Security 或 Spring AI ChatModel 适配器时只替换这里的 Bean。
 */
@Configuration
public class QueryonConfiguration {

    @Bean
    public SemanticRegistry semanticRegistry() {
        return SemanticRegistry.standard();
    }

    @Bean
    public AuditLog auditLog() {
        return new InMemoryAuditLog();
    }

    /** 指标版本化与审批（D2-06）：版本历史只追加，审批通过后才替换语义层当前口径。 */
    @Bean
    public MetricVersionStore metricVersionStore(SemanticRegistry registry, AuditLog auditLog) {
        return new MetricVersionStore(registry, auditLog);
    }

    /** 全入口共用的内存可观测存储（D8-06）。 */
    @Bean
    public ObservationStore observationStore() {
        return new ObservationStore();
    }

    @Bean
    public SampleDataset sampleDataset() {
        return SampleDataGenerator.generate(LocalDate.now());
    }

    /**
     * 执行器由 bootstrap 模块按 queryon.data.source 条件装配：
     * dev 走 PostgreSQL（可叠加 Redis 缓存），默认（含全部测试）回退内存示例数据集。
     */
    @Bean
    public QueryService queryService(SemanticRegistry registry, SampleDataset dataset,
                                     AuditLog auditLog, ObservationStore observationStore,
                                     ObjectProvider<QueryExecutor> executorProvider,
                                     ObjectProvider<QueryMetrics> metricsProvider) {
        QueryExecutor executor = executorProvider.getIfAvailable(
                () -> new InMemoryQueryExecutor(dataset));
        QueryMetrics metrics = metricsProvider.getIfAvailable(() -> QueryMetrics.NOOP);
        return new QueryService(registry, dataset, auditLog, observationStore, executor, metrics);
    }

    @Bean
    public DataProfileService dataProfileService(SemanticRegistry registry, SampleDataset dataset) {
        return new DataProfileService(registry, dataset);
    }

    @Bean
    public DashboardService dashboardService(QueryService queryService) {
        return new DashboardService(queryService);
    }

    @Bean
    public SqlSemanticReader sqlSemanticReader(SemanticRegistry registry) {
        return new SqlSemanticReader(registry);
    }

    /** 默认离线确定性实现；未决项触发后替换为 Spring AI 1.1.x 的 ChatModel 适配器。 */
    @Bean
    public ChatModelPort chatModelPort() {
        return new DeterministicModelClient();
    }

    @Bean
    public ConversationMemory conversationMemory() {
        return new ConversationMemory();
    }

    @Bean
    public AskService askService(QueryService queryService, ChatModelPort chatModelPort,
                                 ConversationMemory memory, AuditLog auditLog,
                                 ObjectProvider<QueryMetrics> metricsProvider) {
        return new AskService(queryService, chatModelPort, memory, auditLog,
                metricsProvider.getIfAvailable(() -> QueryMetrics.NOOP));
    }

    @Bean
    public FeedbackStore feedbackStore() {
        return new FeedbackStore();
    }

    @Bean
    public EvaluationRunner evaluationRunner(AskService askService, FeedbackStore feedbackStore) {
        return new EvaluationRunner(askService, feedbackStore);
    }

    @Bean
    public IdentityResolver identityResolver() {
        return new IdentityResolver();
    }

    /** 将问数工具暴露为 MCP tool：复用 AskService 受治理入口，口径与 REST 一致。 */
    @Bean
    public ToolCallback queryonAskTool(QueryonMcpTools tools) throws NoSuchMethodException {
        Method ask = QueryonMcpTools.class.getMethod("ask", String.class, String.class, String.class);
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.from(ask))
                .toolObject(tools)
                .toolMethod(ask)
                .build();
    }

    /** 将统一查询工具暴露为 MCP tool：复用 QueryService 受治理入口，口径与 REST 一致。 */
    @Bean
    public ToolCallback queryonQueryTool(QueryonMcpTools tools) throws NoSuchMethodException {
        Method query = QueryonMcpTools.class.getMethod("query",
                List.class, List.class, String.class, Map.class, Integer.class, String.class, String.class);
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.from(query))
                .toolObject(tools)
                .toolMethod(query)
                .build();
    }
}
