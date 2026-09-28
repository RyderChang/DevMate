package com.devmate.knowledge.controller;

import com.devmate.common.api.Result;
import com.devmate.common.config.OpenApiConfig;
import com.devmate.knowledge.application.ProcessingService;
import com.devmate.knowledge.dto.ProcessingRequest;
import com.devmate.knowledge.vo.ProcessingResponse;
import com.devmate.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/projects/{projectId}/documents/{documentId}/processing")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name=OpenApiConfig.BEARER_AUTH_SCHEME)
public class ProcessingController {
    private final ProcessingService service;
    public ProcessingController(ProcessingService service) { this.service = service; }
    @Operation(summary="Explicitly process a stored UTF-8 document", description="202 for accepted/in-flight; 200 for a complete generation. Frozen UUID replay; no text in response.")
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<ProcessingResponse>> start(@AuthenticationPrincipal CurrentUser user,
            @PathVariable @Positive Long projectId, @PathVariable @Positive Long documentId, @RequestBody @Valid ProcessingRequest request) {
        var result = service.start(user.id(), projectId, documentId, request.clientRequestId());
        return ResponseEntity.status(result.accepted() ? 202 : 200).body(Result.success(result.response()));
    }
    @Operation(summary="Read latest attempt and active complete generation metadata")
    @GetMapping
    public Result<ProcessingResponse> status(@AuthenticationPrincipal CurrentUser user,
            @PathVariable @Positive Long projectId, @PathVariable @Positive Long documentId) {
        return Result.success(service.status(user.id(), projectId, documentId));
    }
}
