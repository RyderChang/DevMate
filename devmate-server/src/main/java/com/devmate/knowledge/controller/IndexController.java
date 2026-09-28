package com.devmate.knowledge.controller;

import com.devmate.common.api.Result;
import com.devmate.knowledge.application.IndexService;
import com.devmate.knowledge.dto.ProcessingRequest;
import com.devmate.knowledge.vo.IndexResponse;
import com.devmate.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@Validated
@RequestMapping("/projects/{projectId}/documents/{documentId}/indexing")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name="bearerAuth")
public class IndexController {
    private final IndexService service;
    public IndexController(IndexService service){this.service=service;}
    @Operation(summary="Explicitly index a complete active document generation")
    @PostMapping(consumes="application/json")
    public ResponseEntity<Result<IndexResponse>> start(@AuthenticationPrincipal CurrentUser user,@PathVariable @Positive Long projectId,@PathVariable @Positive Long documentId,@RequestBody @Valid ProcessingRequest request){
        var start=service.start(user.id(),projectId,documentId,request.clientRequestId());return ResponseEntity.status(start.accepted()?202:200).body(Result.success(start.response()));
    }
    @Operation(summary="Read latest indexing attempt and current complete active index")
    @GetMapping public Result<IndexResponse> status(@AuthenticationPrincipal CurrentUser user,@PathVariable @Positive Long projectId,@PathVariable @Positive Long documentId){return Result.success(service.status(user.id(),projectId,documentId));}
}
