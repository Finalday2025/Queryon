package com.queryon.web.controller;

import com.queryon.ai.AskResponse;
import com.queryon.ai.AskService;
import com.queryon.common.context.Actor;
import com.queryon.web.dto.AskRequest;
import com.queryon.web.security.IdentityResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 自然语言问数入口；身份由请求头解析，权限过滤由系统注入。 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final AskService askService;
    private final IdentityResolver identityResolver;

    public AskController(AskService askService, IdentityResolver identityResolver) {
        this.askService = askService;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request,
                           @RequestHeader(value = "X-Queryon-User", required = false) String userId) {
        Actor actor = identityResolver.resolve(userId);
        return askService.ask(request.question(), actor, request.sessionId());
    }
}
