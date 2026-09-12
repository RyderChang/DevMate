package com.devmate.database;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseInfrastructureIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DatabaseProbeMapper databaseProbeMapper;

    @Autowired
    private Environment environment;

    @Test
    void connectsToIsolatedMySql8ContainerWithConfiguredHikariPool() throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getMinimumIdle()).isEqualTo(2);
        assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo(MYSQL.getJdbcUrl());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getCatalog()).isEqualTo(MYSQL.getDatabaseName());
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1, @@session.time_zone")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
                assertThat(result.getString(2)).isEqualTo("+00:00");
            }
        }
    }

    @Test
    void appliesAndValidatesMigrationsExactlyOnce() throws Exception {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().current().getScript()).isEqualTo("V4__create_projects_table.sql");
        assertThat(flyway.info().current().getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getTables(
                     connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME"));
            }
        }
        assertThat(tables).containsExactlyInAnyOrder(
                "flyway_schema_history", "users", "role", "permission", "user_role", "role_permission",
                "projects");
    }

    @Test
    void executesMyBatisProbeAgainstTheConfiguredDataSource() {
        assertThat(databaseProbeMapper.selectOne()).isEqualTo(1);
    }
}








