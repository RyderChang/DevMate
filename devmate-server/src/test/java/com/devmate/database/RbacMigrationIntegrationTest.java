package com.devmate.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacMigrationIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsRbacSchemaConstraintsIndexesAndSeedRelations() throws SQLException {
        assertThat(jdbc.queryForList("SELECT code FROM `role` ORDER BY code", String.class))
                .containsExactly("ADMIN", "USER");
        assertThat(jdbc.queryForList("SELECT code FROM `permission` ORDER BY code", String.class))
                .containsExactly("system", "user");
        assertThat(jdbc.queryForList("SELECT CONCAT(r.code, '->', p.code) FROM role_permission rp "
                        + "JOIN `role` r ON r.id=rp.role_id JOIN `permission` p ON p.id=rp.permission_id "
                        + "ORDER BY r.code", String.class))
                .containsExactly("ADMIN->system", "USER->user");

        try (Connection connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(hasIndex(metadata, "user_role", "uk_user_role_user_role")).isTrue();
            assertThat(hasIndex(metadata, "user_role", "idx_user_role_role_id")).isTrue();
            assertThat(hasIndex(metadata, "role_permission", "uk_role_permission_role_permission")).isTrue();
            assertThat(hasIndex(metadata, "role_permission", "idx_role_permission_permission_id")).isTrue();
            assertThat(foreignKeyCount(metadata, "user_role")).isEqualTo(2);
            assertThat(foreignKeyCount(metadata, "role_permission")).isEqualTo(2);
        }
    }

    @Test
    void rejectsDuplicateRelations() {
        jdbc.update("INSERT INTO users(username,password) VALUES ('migration-check', 'hash')");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username='migration-check'", Long.class);
        Long roleId = jdbc.queryForObject("SELECT id FROM `role` WHERE code='USER'", Long.class);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES (?,?)", userId, roleId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES (?,?)", userId, roleId))
                .isInstanceOf(DuplicateKeyException.class);
        Long permissionId = jdbc.queryForObject("SELECT id FROM `permission` WHERE code='user'", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO role_permission(role_id,permission_id) VALUES (?,?)",
                roleId, permissionId)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void assignsUserRoleWhenUpgradingARevisionTwoDatabase() throws Exception {
        String database = "rbac_backfill";
        try (Connection connection = rootConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            statement.execute("CREATE DATABASE `" + database + "`");
            statement.execute("GRANT ALL PRIVILEGES ON `" + database + "`.* TO '"
                    + MYSQL.getUsername() + "'@'%'");
        }
        String url = MYSQL.getJdbcUrl().replace(MYSQL.getDatabaseName(), database);
        try {
            Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("2").load().migrate();
            try (Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
                 var statement = connection.prepareStatement(
                         "INSERT INTO users(username,password) VALUES ('existing-before-rbac','hash')")) {
                statement.executeUpdate();
            }
            Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
            try (Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
                 var statement = connection.prepareStatement("SELECT r.code FROM user_role ur "
                         + "JOIN users u ON u.id=ur.user_id JOIN `role` r ON r.id=ur.role_id "
                         + "WHERE u.username='existing-before-rbac'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("USER");
                assertThat(result.next()).isFalse();
            }
        } finally {
            try (Connection connection = rootConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    private Connection rootConnection() throws SQLException {
        String url = MYSQL.getJdbcUrl().replace(MYSQL.getDatabaseName(), "mysql");
        return DriverManager.getConnection(url, "root", MYSQL.getPassword());
    }

    private boolean hasIndex(java.sql.DatabaseMetaData metadata, String table, String index) throws SQLException {
        try (var result = metadata.getIndexInfo(null, null, table, false, false)) {
            while (result.next()) if (index.equals(result.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    private int foreignKeyCount(java.sql.DatabaseMetaData metadata, String table) throws SQLException {
        int count = 0;
        try (var result = metadata.getImportedKeys(null, null, table)) {
            while (result.next()) count++;
        }
        return count;
    }
}
