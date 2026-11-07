package com.queryon.ai.conversation;

import com.queryon.semantic.model.SemanticQuery;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 多轮对话上下文：继承指标与时间窗，替换或新增维度（D4-04）。 */
public final class ConversationMemory {

    private final Map<String, SemanticQuery> lastQuery = new ConcurrentHashMap<>();

    public Optional<SemanticQuery> recall(String sessionId) {
        return Optional.ofNullable(lastQuery.get(sessionId));
    }

    public void remember(String sessionId, SemanticQuery query) {
        lastQuery.put(sessionId, query);
    }

    public void clear(String sessionId) {
        lastQuery.remove(sessionId);
    }
}
