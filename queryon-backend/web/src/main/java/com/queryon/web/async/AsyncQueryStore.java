package com.queryon.web.async;

import com.queryon.common.context.Actor;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.query.QueryService;
import com.queryon.query.SqlSemanticReader;
import com.queryon.semantic.model.SemanticQuery;
import com.queryon.web.dto.QueryRequest;
import com.queryon.web.dto.QueryResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步长查询任务台账（D3-08）：进程内执行，状态可轮询、可取消；
 * 所有任务仍走 QueryService 受治理入口，四道护栏与权限随人不旁路。
 * 台账为内存结构，重启即失效；任务按身份隔离，跨身份读取一律拒绝。
 */
@Component
public class AsyncQueryStore {

    public record TaskView(String id, String status, QueryResponse result, String error) {
    }

    private static final long RETENTION_MS = 10 * 60 * 1000L;

    private static final class Task {
        private final String id;
        private final String actorId;
        private final long submittedAt = System.currentTimeMillis();
        private volatile String status = "RUNNING";
        private volatile QueryResponse result;
        private volatile String error;
        private volatile CompletableFuture<QueryResponse> future;

        private Task(String id, String actorId) {
            this.id = id;
            this.actorId = actorId;
        }
    }

    private final QueryService queryService;
    private final SqlSemanticReader sqlSemanticReader;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "queryon-async-query");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncQueryStore(QueryService queryService, SqlSemanticReader sqlSemanticReader) {
        this.queryService = queryService;
        this.sqlSemanticReader = sqlSemanticReader;
    }

    public TaskView submit(QueryRequest request, Actor actor) {
        if (request == null) {
            throw new QueryonException(ErrorCode.SEMANTIC_GATE_REJECT, "语义门",
                    "缺少查询定义", "请提供 metrics／dimensions 或 sql");
        }
        cleanup();
        Task task = new Task("q-" + sequence.incrementAndGet(), actor.userId());
        tasks.put(task.id, task);
        task.future = CompletableFuture.supplyAsync(() -> execute(request, actor), workers)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        task.result = response;
                        task.status = "DONE";
                    } else {
                        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                        task.error = cause.getMessage() == null ? cause.getClass().getSimpleName()
                                : cause.getMessage();
                        task.status = "FAILED";
                    }
                });
        return view(task);
    }

    private QueryResponse execute(QueryRequest request, Actor actor) {
        SemanticQuery query = request.sql() != null && !request.sql().isBlank()
                ? sqlSemanticReader.read(request.sql())
                : request.toSemanticQuery(LocalDate.now());
        return QueryResponse.from(queryService.execute(query, actor));
    }

    public TaskView get(String id, Actor actor) {
        return view(require(id, actor));
    }

    public TaskView cancel(String id, Actor actor) {
        Task task = require(id, actor);
        CompletableFuture<QueryResponse> future = task.future;
        if (future != null && !future.isDone()) {
            future.cancel(true);
            task.status = "CANCELLED";
        }
        return view(task);
    }

    private Task require(String id, Actor actor) {
        Task task = tasks.get(id);
        if (task == null || !task.actorId.equals(actor.userId())) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "异步查询",
                    "任务不存在或当前身份无权访问", "异步任务按提问者身份隔离");
        }
        return task;
    }

    private static TaskView view(Task task) {
        return new TaskView(task.id, task.status, task.result, task.error);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            Task task = entry.getValue();
            boolean finished = "DONE".equals(task.status) || "FAILED".equals(task.status)
                    || "CANCELLED".equals(task.status);
            return finished && now - task.submittedAt > RETENTION_MS;
        });
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
