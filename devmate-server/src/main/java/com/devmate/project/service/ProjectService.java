package com.devmate.project.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.api.PageResult;
import com.devmate.common.exception.BusinessException;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.dto.UpdateProjectRequest;
import com.devmate.project.entity.ProjectEntity;
import com.devmate.project.mapper.ProjectMapper;
import com.devmate.project.vo.ProjectResponse;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_PAGE_SIZE = 100;

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Transactional
    public ProjectResponse create(Long currentUserId, CreateProjectRequest request) {
        requireCurrentUser(currentUserId);
        Objects.requireNonNull(request, "request must not be null");
        ProjectEntity project = new ProjectEntity();
        project.setOwnerUserId(currentUserId);
        project.setName(normalizeName(request.name()));
        project.setDescription(normalizeDescription(request.description()));
        if (projectMapper.insertProject(project) != 1 || project.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return toResponse(findOwnedActiveProject(currentUserId, project.getId()));
    }

    @Transactional(readOnly = true)
    public PageResult<ProjectResponse> list(Long currentUserId, int page, int pageSize) {
        requireCurrentUser(currentUserId);
        validatePagination(page, pageSize);
        long offset = Math.multiplyExact((long) page - 1L, (long) pageSize);
        long total = projectMapper.countOwnedActive(currentUserId);
        List<ProjectResponse> items = projectMapper.findOwnedActivePage(currentUserId, offset, pageSize)
                .stream().map(this::toResponse).toList();
        return new PageResult<>(page, pageSize, total, items);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long currentUserId, Long projectId) {
        return toResponse(findOwnedActiveProject(currentUserId, projectId));
    }

    @Transactional
    public ProjectResponse update(Long currentUserId, Long projectId, UpdateProjectRequest request) {
        requireCurrentUser(currentUserId);
        requireProjectId(projectId);
        Objects.requireNonNull(request, "request must not be null");
        String name = normalizeName(request.name());
        String description = normalizeDescription(request.description());
        if (projectMapper.updateOwnedActive(projectId, currentUserId, name, description) == 0) {
            throw projectNotFound();
        }
        return toResponse(findOwnedActiveProject(currentUserId, projectId));
    }

    @Transactional
    public void delete(Long currentUserId, Long projectId) {
        requireCurrentUser(currentUserId);
        requireProjectId(projectId);
        if (projectMapper.softDeleteOwnedActive(projectId, currentUserId) == 0) {
            throw projectNotFound();
        }
    }

    @Transactional(readOnly = true)
    public void requireOwnedActiveProject(Long currentUserId, Long projectId) {
        findOwnedActiveProject(currentUserId, projectId);
    }

    private ProjectEntity findOwnedActiveProject(Long currentUserId, Long projectId) {
        requireCurrentUser(currentUserId);
        requireProjectId(projectId);
        ProjectEntity project = projectMapper.findOwnedActiveById(projectId, currentUserId);
        if (project == null) {
            throw projectNotFound();
        }
        return project;
    }

    private void validatePagination(int page, int pageSize) {
        if (page < 1 || page > MAX_PAGE || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = description.strip();
        if (normalized.length() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private void requireCurrentUser(Long currentUserId) {
        Objects.requireNonNull(currentUserId, "currentUserId must not be null");
    }

    private void requireProjectId(Long projectId) {
        if (projectId == null || projectId < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private BusinessException projectNotFound() {
        return new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
    }

    private ProjectResponse toResponse(ProjectEntity project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                project.getCreateTime().toInstant(ZoneOffset.UTC),
                project.getUpdateTime().toInstant(ZoneOffset.UTC));
    }
}
