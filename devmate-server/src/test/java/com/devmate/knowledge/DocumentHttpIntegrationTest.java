package com.devmate.knowledge;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.service.ProjectService;
import com.devmate.security.CurrentUser;
import com.devmate.security.JwtService;
import com.devmate.service.UserRoleService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Import(DocumentLifecycleIntegrationTest.StorageConfiguration.class)
class DocumentHttpIntegrationTest extends MySqlIntegrationTestBase {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired UserRoleService roles;
    @Autowired JwtService jwt;
    @Autowired com.devmate.knowledge.infrastructure.UploadTempFiles temporary;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) { DocumentLifecycleIntegrationTest.knowledgeConfiguration(registry); }

    @Test void realServletEnforcesFileAndRequestBoundsAndMapsMalformedMultipart() throws Exception {
        String username = "http-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO users(username,password) VALUES (?,?)", username, UUID.randomUUID().toString());
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username); roles.assignDefaultRole(owner);
        long project = projects.create(owner, new CreateProjectRequest("Synthetic", null)).id();
        String token = "Bearer " + jwt.generate(new CurrentUser(owner, username, List.of("USER")));
        String boundary = "devmate-" + UUID.randomUUID();
        try (var client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build()) {
            for (int size : new int[]{5 * 1024 * 1024, 5 * 1024 * 1024 + 1, 6 * 1024 * 1024}) {
                String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"clientRequestId\"\r\n\r\n" + UUID.randomUUID()
                        + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.txt\"\r\nContent-Type: text/plain\r\n\r\n";
                byte[] payload = (prefix + "x".repeat(size) + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/projects/" + project + "/documents"))
                        .header("Authorization", token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(size == 5 * 1024 * 1024 ? 200 : 413);
                assertThat(response.body()).doesNotContain("/tmp/", "objectKey", "Exception", "synthetic.txt" + ":");
                assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
            }
            var malformed = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/projects/" + project + "/documents"))
                    .header("Authorization", token).header("Content-Type", "multipart/form-data").timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("synthetic malformed multipart")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(malformed.statusCode()).isEqualTo(400); assertThat(malformed.body()).doesNotContain("Exception", "/tmp/", "synthetic malformed");
            for (String unsupported : new String[]{"/auth/login", "/projects/" + project + "/documents"}) {
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + unsupported))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary).timeout(Duration.ofSeconds(5));
                if (!unsupported.equals("/auth/login")) builder.header("Authorization", token).method("GET", HttpRequest.BodyPublishers.ofString("synthetic"));
                else builder.POST(HttpRequest.BodyPublishers.ofString("synthetic"));
                assertThat(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
            }
            try (var files = java.nio.file.Files.list(temporary.multipartDirectory())) { assertThat(files.count()).isZero(); }
        }
    }
}
