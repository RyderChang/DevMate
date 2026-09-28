package com.devmate.database;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MySqlIntegrationTestBase {
    private static final String JWT_SECRET = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("devmate.jwt.secret",
                () -> JWT_SECRET);
        registry.add("devmate.jwt.expiration",
                () -> "PT1H");
    }

    @Container
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(org.testcontainers.utility.DockerImageName.parse(
                    "mysql@sha256:c296d65ee6ab3ce2f608c1d1b2bdd3c08b087a5834101d76a6db2e00875216cc").asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("devmate_test")
                    .withUsername("devmate_test")
                    .withPassword(java.util.UUID.randomUUID().toString())
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                                    new com.github.dockerjava.api.model.ExposedPort(3306))))
                    .withCommand("--default-time-zone=+00:00");
}
