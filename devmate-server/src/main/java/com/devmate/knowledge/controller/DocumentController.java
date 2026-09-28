package com.devmate.knowledge.controller;

import com.devmate.common.api.*;
import com.devmate.common.config.OpenApiConfig;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.application.DocumentService;
import com.devmate.knowledge.vo.DocumentResponse;
import com.devmate.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

@Validated
@RestController
@RequestMapping("/projects/{projectId}/documents")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }

    @Operation(summary = "Store one UTF-8 txt/md file", description = "Exactly one file and one UUID clientRequestId form field. "
            + "5 MiB file / 6 MiB request maximum. Same UUID and fingerprint returns the stored original (200), or 409 while uncertain or terminated. "
            + "Terminal mappings expire 24 hours after physical cleanup; after expiry the UUID can create a new document. STORED means raw bytes only.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DocumentResponse> upload(@AuthenticationPrincipal CurrentUser user, @PathVariable @Positive Long projectId,
                                           @RequestPart("file") MultipartFile file,
                                           @Parameter(description = "Original request UUID; reuse it after an uncertain response", schema = @Schema(format = "uuid"))
                                           @RequestParam("clientRequestId") String clientRequestId,
                                           @Parameter(hidden = true) MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().size() != 1 || !request.getMultiFileMap().containsKey("file")
                || request.getFiles("file").size() != 1 || request.getParameterMap().size() != 1
                || request.getParameterValues("clientRequestId") == null || request.getParameterValues("clientRequestId").length != 1
                || request.getQueryString() != null) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return Result.success(service.upload(user.id(), projectId, clientRequestId, file));
    }
    @GetMapping
    public Result<PageResult<DocumentResponse>> list(@AuthenticationPrincipal CurrentUser user, @PathVariable @Positive Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return Result.success(service.list(user.id(), projectId, page, pageSize));
    }
    @GetMapping("/{documentId}")
    public Result<DocumentResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable @Positive Long projectId,
                                        @PathVariable @Positive Long documentId) {
        return Result.success(service.get(user.id(), projectId, documentId));
    }
    @DeleteMapping("/{documentId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Deletion accepted; pending objects remain reserved until cleanup is confirmed")
    public ResponseEntity<Result<Void>> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable @Positive Long projectId,
                                               @PathVariable @Positive Long documentId) {
        service.delete(user.id(), projectId, documentId);
        return ResponseEntity.accepted().body(Result.success());
    }
}
