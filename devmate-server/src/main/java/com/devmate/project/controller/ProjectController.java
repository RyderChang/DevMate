package com.devmate.project.controller;

import com.devmate.common.config.OpenApiConfig;
import com.devmate.common.api.PageResult;
import com.devmate.common.api.Result;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.dto.UpdateProjectRequest;
import com.devmate.project.service.ProjectService;
import com.devmate.project.vo.ProjectResponse;
import com.devmate.security.CurrentUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/projects")
@PreAuthorize("hasAuthority('user')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public Result<ProjectResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                          @Valid @RequestBody CreateProjectRequest request) {
        return Result.success(projectService.create(currentUser.id(), request));
    }

    @GetMapping
    public Result<PageResult<ProjectResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "1") @Min(1) @Max(ProjectService.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ProjectService.MAX_PAGE_SIZE) int pageSize) {
        return Result.success(projectService.list(currentUser.id(), page, pageSize));
    }

    @GetMapping("/{projectId}")
    public Result<ProjectResponse> get(@AuthenticationPrincipal CurrentUser currentUser,
                                       @PathVariable @Positive Long projectId) {
        return Result.success(projectService.get(currentUser.id(), projectId));
    }

    @PutMapping("/{projectId}")
    public Result<ProjectResponse> update(@AuthenticationPrincipal CurrentUser currentUser,
                                          @PathVariable @Positive Long projectId,
                                          @Valid @RequestBody UpdateProjectRequest request) {
        return Result.success(projectService.update(currentUser.id(), projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser currentUser,
                               @PathVariable @Positive Long projectId) {
        projectService.delete(currentUser.id(), projectId);
        return Result.success();
    }
}
