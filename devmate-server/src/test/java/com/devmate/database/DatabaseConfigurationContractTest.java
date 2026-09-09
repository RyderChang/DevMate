package com.devmate.database;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigurationContractTest {

    @Test
    void enablesDataSourceAndSecurity() throws IOException {
        String config = resource("/application.yml");

        assertThat(config)
                .doesNotContain("SecurityAutoConfiguration")
                .doesNotContain("DataSourceAutoConfiguration")
                .contains("map-underscore-to-camel-case: true")
                .contains("clean-disabled: true")
                .contains("baseline-on-migrate: false")
                .contains("out-of-order: false");
        assertThat(config).contains("secret: ${JWT_SECRET:}").contains("expiration: ${JWT_EXPIRATION:PT2H}");
    }

    @Test
    void productionDatabaseCredentialsHaveNoFallbackValues() throws IOException {
        String config = resource("/application-prod.yml");

        assertThat(config)
                .contains("url: ${DB_URL}")
                .contains("username: ${DB_USERNAME}")
                .contains("password: ${DB_PASSWORD}")
                .doesNotContain("${DB_PASSWORD:");
    }

    private String resource(String path) throws IOException {
        try (var input = DatabaseConfigurationContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
