package com.devmate.project;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.entity.UserEntity;
import com.devmate.mapper.UserMapper;
import com.devmate.project.entity.ProjectEntity;
import com.devmate.project.mapper.ProjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectMapperIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired ProjectMapper mapper;
    @Autowired UserMapper userMapper;
    @Autowired JdbcTemplate jdbc;
    Long ownerId;
    Long otherId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM projects");
        userMapper.delete(null);
        ownerId = insertUser("project-owner");
        otherId = insertUser("other-owner");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM projects");
        userMapper.delete(null);
    }

    @Test
    void insertsAndRestrictsDetailsToOwner() {
        ProjectEntity project = insertProject(ownerId, "Workspace", "Details");
        assertThat(project.getId()).isNotNull();

        ProjectEntity found = mapper.findOwnedActiveById(project.getId(), ownerId);
        assertThat(found.getName()).isEqualTo("Workspace");
        assertThat(found.getCreateTime()).isNotNull();
        assertThat(mapper.findOwnedActiveById(project.getId(), otherId)).isNull();
    }

    @Test
    void pagesOnlyActiveOwnedProjectsWithStableOrdering() {
        ProjectEntity first = insertProject(ownerId, "First", null);
        ProjectEntity second = insertProject(ownerId, "Second", null);
        insertProject(otherId, "Other", null);
        LocalDateTime sameTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        jdbc.update("UPDATE projects SET update_time=? WHERE owner_user_id=?", sameTime, ownerId);

        assertThat(mapper.countOwnedActive(ownerId)).isEqualTo(2);
        assertThat(mapper.findOwnedActivePage(ownerId, 0, 1))
                .extracting(ProjectEntity::getId).containsExactly(second.getId());
        assertThat(mapper.findOwnedActivePage(ownerId, 1, 1))
                .extracting(ProjectEntity::getId).containsExactly(first.getId());

        assertThat(mapper.softDeleteOwnedActive(second.getId(), ownerId)).isOne();
        assertThat(mapper.countOwnedActive(ownerId)).isOne();
        assertThat(mapper.findOwnedActivePage(ownerId, 0, 10))
                .extracting(ProjectEntity::getId).containsExactly(first.getId());
    }

    @Test
    void updatesAndSoftDeletesOnlyOwnedActiveProjects() {
        ProjectEntity project = insertProject(ownerId, "Before", null);

        assertThat(mapper.updateOwnedActive(project.getId(), otherId, "Hacked", null)).isZero();
        assertThat(mapper.updateOwnedActive(project.getId(), ownerId, "After", "Updated")).isOne();
        assertThat(mapper.findOwnedActiveById(project.getId(), ownerId).getName()).isEqualTo("After");
        assertThat(mapper.softDeleteOwnedActive(project.getId(), otherId)).isZero();
        assertThat(mapper.softDeleteOwnedActive(project.getId(), ownerId)).isOne();
        assertThat(mapper.softDeleteOwnedActive(project.getId(), ownerId)).isZero();
        assertThat(mapper.findOwnedActiveById(project.getId(), ownerId)).isNull();
        assertThat(jdbc.queryForObject("SELECT delete_time IS NOT NULL FROM projects WHERE id=?",
                Boolean.class, project.getId())).isTrue();
    }

    private Long insertUser(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword("persistence-only-hash");
        userMapper.insert(user);
        return user.getId();
    }

    private ProjectEntity insertProject(Long owner, String name, String description) {
        ProjectEntity project = new ProjectEntity();
        project.setOwnerUserId(owner);
        project.setName(name);
        project.setDescription(description);
        assertThat(mapper.insertProject(project)).isOne();
        return project;
    }
}
