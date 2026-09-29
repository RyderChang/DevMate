package com.devmate.knowledge.controller;

import com.devmate.common.api.Result;
import com.devmate.knowledge.application.RetrievalService;
import com.devmate.knowledge.dto.RetrievalRequest;
import com.devmate.knowledge.vo.RetrievalResponse;
import com.devmate.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@Validated
@RequestMapping("/projects/{projectId}/knowledge/search")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name="bearerAuth")
public class RetrievalController {
    private final RetrievalService service;
    public RetrievalController(RetrievalService service){this.service=service;}
    @Operation(summary="Retrieve authorized complete document chunks with bounded replenishment")
    @PostMapping(consumes="application/json") public Result<RetrievalResponse> search(@AuthenticationPrincipal CurrentUser user,@PathVariable @Positive Long projectId,@RequestBody @Valid RetrievalRequest request){return Result.success(service.search(user.id(),projectId,request));}
}
