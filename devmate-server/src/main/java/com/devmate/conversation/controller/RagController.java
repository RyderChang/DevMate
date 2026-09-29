package com.devmate.conversation.controller;

import com.devmate.common.api.Result;
import com.devmate.common.config.OpenApiConfig;
import com.devmate.conversation.dto.SendMessageRequest;
import com.devmate.conversation.dto.RagMessageRequest;
import com.devmate.conversation.service.RagService;
import com.devmate.conversation.vo.RagMessageResponse;
import com.devmate.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name=OpenApiConfig.BEARER_AUTH_SCHEME)
public class RagController {
    private final RagService service;
    public RagController(RagService service) { this.service=service; }
    @Operation(summary="Send a bounded RAG message with server-verified document citations")
    @PostMapping("/projects/{projectId}/conversations/{conversationId}/rag-messages")
    public Result<RagMessageResponse> send(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable @Positive Long projectId,
                                           @PathVariable @Positive Long conversationId,
                                           @Valid @RequestBody RagMessageRequest request) {
        return Result.success(service.send(user.id(),projectId,conversationId,
                new SendMessageRequest(request.clientRequestId(),request.content())));
    }
}
