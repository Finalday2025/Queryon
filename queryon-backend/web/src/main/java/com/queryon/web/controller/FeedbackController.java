package com.queryon.web.controller;

import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import com.queryon.evaluation.FeedbackEntry;
import com.queryon.evaluation.FeedbackStore;
import com.queryon.semantic.SemanticRegistry;
import com.queryon.web.dto.FeedbackRequest;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 反馈闭环（D4-10）：纠正样本回流后进入下一次 golden set 回归，不改写既有样本语义。 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final FeedbackStore feedbackStore;
    private final SemanticRegistry registry;
    private final IdentityResolver identityResolver;

    public FeedbackController(FeedbackStore feedbackStore, SemanticRegistry registry,
                              IdentityResolver identityResolver) {
        this.feedbackStore = feedbackStore;
        this.registry = registry;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/feedback")
    public FeedbackEntry submit(@RequestBody FeedbackRequest request,
                                @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        String metric = request.correctedMetric();
        if (metric == null || !registry.hasMetric(metric)) {
            throw new QueryonException(ErrorCode.UNKNOWN_TERM, "反馈入口",
                    "纠正指标不是受管指标", "请从 GET /api/semantic/metrics 中选择认证指标编码");
        }
        feedbackStore.record(request.question(), metric, request.note());
        return feedbackStore.entries().getLast();
    }

    @GetMapping("/feedback")
    public List<FeedbackEntry> list(@RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return feedbackStore.entries();
    }
}
