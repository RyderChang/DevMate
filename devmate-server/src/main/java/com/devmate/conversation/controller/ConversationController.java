package com.devmate.conversation.controller;

import com.devmate.common.api.PageResult;
import com.devmate.common.api.Result;
import com.devmate.common.config.OpenApiConfig;
import com.devmate.conversation.dto.CreateConversationRequest;
import com.devmate.conversation.dto.SendMessageRequest;
import com.devmate.conversation.service.ConversationService;
import com.devmate.conversation.vo.ConversationResponse;
import com.devmate.conversation.vo.MessageResponse;
import com.devmate.conversation.vo.SendMessageResponse;
import com.devmate.security.CurrentUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/projects/{projectId}/conversations")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Result<ConversationResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                               @PathVariable @Positive Long projectId,
                                               @Valid @RequestBody CreateConversationRequest request) {
        return Result.success(conversationService.create(currentUser.id(), projectId, request));
    }

    @GetMapping
    public Result<PageResult<ConversationResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(ConversationService.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConversationService.MAX_PAGE_SIZE) int pageSize) {
        return Result.success(conversationService.list(currentUser.id(), projectId, page, pageSize));
    }

    @GetMapping("/{conversationId}")
    public Result<ConversationResponse> get(@AuthenticationPrincipal CurrentUser currentUser,
                                            @PathVariable @Positive Long projectId,
                                            @PathVariable @Positive Long conversationId) {
        return Result.success(conversationService.get(currentUser.id(), projectId, conversationId));
    }

    @GetMapping("/{conversationId}/messages")
    public Result<PageResult<MessageResponse>> messages(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Positive Long projectId,
            @PathVariable @Positive Long conversationId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(ConversationService.MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(ConversationService.MAX_PAGE_SIZE) int pageSize) {
        return Result.success(conversationService.listMessages(
                currentUser.id(), projectId, conversationId, page, pageSize));
    }

    @PostMapping("/{conversationId}/messages")
    public Result<SendMessageResponse> send(@AuthenticationPrincipal CurrentUser currentUser,
                                            @PathVariable @Positive Long projectId,
                                            @PathVariable @Positive Long conversationId,
                                            @Valid @RequestBody SendMessageRequest request) {
        return Result.success(conversationService.send(currentUser.id(), projectId, conversationId, request));
    }
}
