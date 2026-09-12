package com.devmate.project;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.dto.UpdateProjectRequest;
import com.devmate.project.entity.ProjectEntity;
import com.devmate.project.mapper.ProjectMapper;
import com.devmate.project.service.ProjectService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {
    @Mock ProjectMapper mapper;
    ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(mapper);
    }

    @Test
    void createsForCurrentUserAndNormalizesValues() {
        when(mapper.insertProject(any())).thenAnswer(invocation -> {
            ProjectEntity project = invocation.getArgument(0);
            project.setId(10L);
            return 1;
        });
        when(mapper.findOwnedActiveById(10L, 7L)).thenReturn(project(10L, 7L, "Workspace", "Details"));

        var response = service.create(7L, new CreateProjectRequest("  Workspace  ", "  Details  "));

        assertThat(response.id()).isEqualTo(10L);
        var captor = org.mockito.ArgumentCaptor.forClass(ProjectEntity.class);
        verify(mapper).insertProject(captor.capture());
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getName()).isEqualTo("Workspace");
        assertThat(captor.getValue().getDescription()).isEqualTo("Details");
    }

    @Test
    void validatesNormalizedLengthsAndConvertsBlankDescriptionToNull() {
        when(mapper.insertProject(any())).thenAnswer(invocation -> {
            ProjectEntity project = invocation.getArgument(0);
            project.setId(1L);
            return 1;
        });
        when(mapper.findOwnedActiveById(1L, 2L)).thenReturn(project(1L, 2L, "x".repeat(100), null));

        service.create(2L, new CreateProjectRequest("  " + "x".repeat(100) + "  ", "   "));
        var captor = org.mockito.ArgumentCaptor.forClass(ProjectEntity.class);
        verify(mapper).insertProject(captor.capture());
        assertThat(captor.getValue().getDescription()).isNull();

        assertInvalid(() -> service.create(2L, new CreateProjectRequest("x".repeat(101), null)));
        assertInvalid(() -> service.create(2L, new CreateProjectRequest("valid", "x".repeat(1001))));
        assertInvalid(() -> service.create(2L, new CreateProjectRequest("   ", null)));
        assertInvalid(() -> service.create(2L, new CreateProjectRequest("\u3000\u3000", null)));
    }

    @Test
    void normalizesUnicodeWhitespaceWhenUpdating() {
        when(mapper.updateOwnedActive(4L, 2L, "Renamed", null)).thenReturn(1);
        when(mapper.findOwnedActiveById(4L, 2L)).thenReturn(project(4L, 2L, "Renamed", null));

        var response = service.update(2L, 4L,
                new UpdateProjectRequest("\u3000Renamed\u3000", "\u3000\u3000"));

        assertThat(response.name()).isEqualTo("Renamed");
        verify(mapper).updateOwnedActive(4L, 2L, "Renamed", null);
    }

    @Test
    void rejectsAnInsertThatDoesNotCreateExactlyOneProject() {
        when(mapper.insertProject(any())).thenReturn(0);
        assertThatThrownBy(() -> service.create(2L, new CreateProjectRequest("Project", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode()));
    }

    @Test
    void listsOwnedProjectsWithWideOffsetAndImmutableItems() {
        when(mapper.countOwnedActive(4L)).thenReturn(1L);
        when(mapper.findOwnedActivePage(4L, 999_900L, 100))
                .thenReturn(List.of(project(3L, 4L, "Project", null)));

        var page = service.list(4L, 10_000, 100);

        assertThat(page.total()).isOne();
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(3L);
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidPaginationBeforeQueryingMapper() {
        assertInvalid(() -> service.list(1L, 0, 20));
        assertInvalid(() -> service.list(1L, 10_001, 20));
        assertInvalid(() -> service.list(1L, 1, 101));
        verify(mapper, never()).countOwnedActive(any());
    }

    @Test
    void hidesMissingUnauthorizedAndDeletedProjectsBehindSameError() {
        when(mapper.findOwnedActiveById(9L, 1L)).thenReturn(null);
        assertProjectNotFound(() -> service.get(1L, 9L));

        when(mapper.updateOwnedActive(9L, 1L, "Name", null)).thenReturn(0);
        assertProjectNotFound(() -> service.update(1L, 9L, new UpdateProjectRequest("Name", null)));

        when(mapper.softDeleteOwnedActive(9L, 1L)).thenReturn(0);
        assertProjectNotFound(() -> service.delete(1L, 9L));
    }

    @Test
    void exposesOwnershipCheckWithoutReturningPersistenceData() {
        when(mapper.findOwnedActiveById(5L, 3L)).thenReturn(project(5L, 3L, "Owned", null));
        service.requireOwnedActiveProject(3L, 5L);
        verify(mapper).findOwnedActiveById(5L, 3L);
    }

    @Test
    void ownershipCheckHidesOtherDeletedAndMissingProjectsBehindSameError() {
        when(mapper.findOwnedActiveById(5L, 3L)).thenReturn(null);
        when(mapper.findOwnedActiveById(6L, 3L)).thenReturn(null);
        when(mapper.findOwnedActiveById(7L, 3L)).thenReturn(null);

        assertProjectNotFound(() -> service.requireOwnedActiveProject(3L, 5L));
        assertProjectNotFound(() -> service.requireOwnedActiveProject(3L, 6L));
        assertProjectNotFound(() -> service.requireOwnedActiveProject(3L, 7L));
    }

    @Test
    void doesNotSwallowMapperWriteFailures() {
        when(mapper.insertProject(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.create(2L, new CreateProjectRequest("Project", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    private ProjectEntity project(Long id, Long ownerId, String name, String description) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setOwnerUserId(ownerId);
        project.setName(name);
        project.setDescription(description);
        project.setDeleted(false);
        project.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        project.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return project;
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.INVALID_PARAMETER.getCode()));
    }

    private void assertProjectNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND.getCode()));
    }
}
