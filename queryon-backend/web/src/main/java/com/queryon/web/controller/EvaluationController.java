package com.queryon.web.controller;

import com.queryon.evaluation.EvaluationPerformance;
import com.queryon.evaluation.EvaluationReport;
import com.queryon.evaluation.EvaluationRunner;
import com.queryon.evaluation.ReportRenderer;
import com.queryon.web.security.IdentityResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 评测运行器入口：一键回归并输出可重复生成的版本报告；评测不过即返回 409，阻断发布。 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationRunner runner;
    private final IdentityResolver identityResolver;

    public EvaluationController(EvaluationRunner runner, IdentityResolver identityResolver) {
        this.runner = runner;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/run")
    public EvaluationReport run(@RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        EvaluationReport report = runner.run();
        if (!report.allPassed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "评测存在失败样本，阻断发布");
        }
        return report;
    }

    /**
     * 最近一次运行的性能观测（D6-08 延迟）：与可重复报告分离，不参与门禁比较。
     * 尚未运行过时返回 {@code available=false}，不返回任何估算值。
     */
    @GetMapping("/performance")
    public Map<String, Object> performance(
            @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        EvaluationPerformance performance = runner.lastPerformance();
        Map<String, Object> result = new LinkedHashMap<>();
        if (performance == null) {
            result.put("available", false);
            return result;
        }
        result.put("available", true);
        result.put("performance", performance);
        return result;
    }

    @GetMapping(value = "/report", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String report(@RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        identityResolver.resolve(userId);
        return ReportRenderer.render(runner.run());
    }
}
