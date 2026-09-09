package com.devmate.database;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class MySqlIntegrationTestBase {

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("devmate.jwt.secret", () -> "test-only-jwt-secret-that-is-at-least-32-bytes-long");
        registry.add("devmate.jwt.expiration", () -> "PT1H");
    }

    @Container
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("devmate_test")
            .withUsername("devmate_test")
            .withPassword("test-only-password");
}
