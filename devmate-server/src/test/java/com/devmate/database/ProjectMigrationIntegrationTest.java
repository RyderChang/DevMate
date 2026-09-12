package com.devmate.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectMigrationIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void createsProjectColumnsConstraintIndexAndForeignKey() throws SQLException {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='projects'", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name='projects' "
                        + "AND index_name='idx_projects_owner_deleted_updated_id' ORDER BY seq_in_index",
                String.class)).containsExactly("owner_user_id", "deleted", "update_time", "id");
        assertThat(jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema=DATABASE() AND table_name='projects' "
                        + "AND constraint_name='fk_projects_owner_user'", String.class))
                .isEqualTo("RESTRICT");
        assertThat(jdbc.queryForObject("SELECT referenced_table_name FROM information_schema.key_column_usage "
                        + "WHERE constraint_schema=DATABASE() AND table_name='projects' "
                        + "AND constraint_name='fk_projects_owner_user'", String.class))
                .isEqualTo("users");
        assertColumn("id", "bigint", "NO", null, null, "auto_increment");
        assertColumn("owner_user_id", "bigint", "NO", null, null, "");
        assertColumn("name", "varchar", "NO", 100L, null, "");
        assertColumn("description", "varchar", "YES", 1000L, null, "");
        assertColumn("deleted", "tinyint", "NO", null, "0", "");
        assertColumn("create_time", "timestamp", "NO", null, "CURRENT_TIMESTAMP(6)", "DEFAULT_GENERATED");
        assertColumn("update_time", "timestamp", "NO", null, "CURRENT_TIMESTAMP(6)",
                "DEFAULT_GENERATED on update CURRENT_TIMESTAMP(6)");
        assertColumn("delete_time", "timestamp", "YES", null, null, "");

        try (Connection connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            try (var primaryKeys = metadata.getPrimaryKeys(null, null, "projects")) {
                assertThat(primaryKeys.next()).isTrue();
                assertThat(primaryKeys.getString("PK_NAME")).isEqualTo("PRIMARY");
                assertThat(primaryKeys.getString("COLUMN_NAME")).isEqualTo("id");
            }
        }
    }

    @Test
    void enforcesDeleteFlagAndRestrictsDeletingOwner() {
        jdbc.update("INSERT INTO users(username,password) VALUES ('migration-owner','hash')");
        Long ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username='migration-owner'", Long.class);
        jdbc.update("INSERT INTO projects(owner_user_id,name) VALUES (?, 'Migration')", ownerId);

        assertThat(jdbc.queryForObject("SELECT deleted FROM projects WHERE owner_user_id=?",
                Integer.class, ownerId)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE projects SET deleted=2 WHERE owner_user_id=?", ownerId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id=?", ownerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void validatesAllMigrationsThroughVersionFour() {
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE success=1 ORDER BY installed_rank", String.class))
                .contains("1", "2", "3", "4");
    }

    private void assertColumn(String name, String dataType, String nullable, Long maximumLength,
                              String defaultValue, String extra) {
        var column = jdbc.queryForMap("SELECT data_type, is_nullable, character_maximum_length, "
                + "column_default, extra FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='projects' AND column_name=?", name);
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("is_nullable")).isEqualTo(nullable);
        assertThat(column.get("character_maximum_length")).isEqualTo(maximumLength);
        assertThat(column.get("column_default")).isEqualTo(defaultValue);
        assertThat(String.valueOf(column.get("extra"))).isEqualToIgnoringCase(extra);
    }
}
