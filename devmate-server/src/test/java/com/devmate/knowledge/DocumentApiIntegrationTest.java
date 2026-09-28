package com.devmate.knowledge;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.knowledge.application.DocumentService;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.mapper.RoleMapper;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.service.ProjectService;
import com.devmate.security.CurrentUser;
import com.devmate.security.JwtService;
import com.devmate.service.UserRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(DocumentLifecycleIntegrationTest.StorageConfiguration.class)
class DocumentApiIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired DocumentService service;
    @Autowired UserRoleService roles;
    @Autowired RoleMapper roleMapper;
    @Autowired JwtService jwt;
    @Autowired KnowledgeProperties properties;
    @Autowired DocumentLifecycleIntegrationTest.StubStorage storage;
    long owner;
    long project;
    String token;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        DocumentLifecycleIntegrationTest.knowledgeConfiguration(registry);
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM knowledge_document_requests"); jdbc.update("DELETE FROM knowledge_documents");
        jdbc.update("DELETE FROM knowledge_project_capacity"); jdbc.update("DELETE FROM projects"); jdbc.update("DELETE FROM users");
        owner = user("api-owner", true); project = projects.create(owner, new CreateProjectRequest("Synthetic", null)).id();
        token = token(owner, "api-owner", "USER"); storage.reset(); properties.setEnabled(true);
    }

    @Test void metadataAndReplaysExposeNoObjectLocatorAndDoNotOverwriteSameName() throws Exception {
        String request = UUID.randomUUID().toString();
        String body = mvc.perform(multipart(path()).file(file("note.md", "# Synthetic"))
                        .param("clientRequestId", request).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storageState").value("STORED"))
                .andExpect(jsonPath("$.data.fileType").value("md")).andExpect(jsonPath("$.data.sourceType").value("UPLOAD"))
                .andExpect(jsonPath("$.data.createTime").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.data.bucket").doesNotExist()).andExpect(jsonPath("$.data.objectKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("data").path("id").asLong();
        mvc.perform(multipart(path()).file(file("note.md", "# Synthetic")).param("clientRequestId", request).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        assertThat(storage.writes.get()).isOne();
        long second = upload("note.md", "# Synthetic"); assertThat(second).isNotEqualTo(id);
        assertThat(storage.objects).hasSize(2);
        mvc.perform(get(path() + "/" + id).header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(delete(path() + "/" + id).header("Authorization", token)).andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete(path() + "/" + id).header("Authorization", token)).andExpect(status().isAccepted());
        mvc.perform(get(path() + "/" + id).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get(path()).header("Authorization", token)).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test void allEntrypointsHideOwnershipMismatchDeletedProjectsAndAdminBypass() throws Exception {
        long id = upload("note.txt", "Synthetic");
        long outsider = user("api-outsider", true); String other = token(outsider, "api-outsider", "USER");
        long otherProject = projects.create(owner, new CreateProjectRequest("Other", null)).id();
        long admin = user("api-admin", true); roles.assignRole(admin, roleMapper.findByCode("ADMIN"));
        String adminToken = token(admin, "api-admin", "ADMIN", "USER");
        int inspections = storage.inspections.get();
        for (String caller : List.of(other, adminToken)) {
            mvc.perform(multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", caller)).andExpect(status().isNotFound());
            mvc.perform(get(path()).header("Authorization", caller)).andExpect(status().isNotFound());
            mvc.perform(get(path() + "/" + id).header("Authorization", caller)).andExpect(status().isNotFound());
            mvc.perform(delete(path() + "/" + id).header("Authorization", caller)).andExpect(status().isNotFound());
        }
        for (String method : List.of("get", "delete")) {
            var request = method.equals("get") ? get("/projects/" + otherProject + "/documents/" + id) : delete("/projects/" + otherProject + "/documents/" + id);
            mvc.perform(request.header("Authorization", token)).andExpect(status().isNotFound());
        }
        projects.delete(owner, project);
        mvc.perform(get(path()).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get(path() + "/" + id).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(delete(path() + "/" + id).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", token)).andExpect(status().isNotFound());
        assertThat(storage.writes.get()).isOne(); assertThat(storage.deletes.get()).isZero(); assertThat(storage.inspections.get()).isEqualTo(inspections);
    }

    @Test void authenticationAndAuthorityAreRequiredBeforeMultipartHandling() throws Exception {
        long noRole = user("api-no-role", false); String noAuthority = token(noRole, "api-no-role", "ADMIN");
        for (var request : List.of(get(path()), get(path() + "/1"), delete(path() + "/1"), multipart(path()).file(file("note.txt", "x")))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", noAuthority))
                .andExpect(status().isForbidden());
        mvc.perform(get(path()).header("Authorization", noAuthority)).andExpect(status().isForbidden());
        mvc.perform(get(path() + "/1").header("Authorization", noAuthority)).andExpect(status().isForbidden());
        mvc.perform(delete(path() + "/1").header("Authorization", noAuthority)).andExpect(status().isForbidden());
        assertThat(storage.writes.get()).isZero();
    }

    @Test void paginationHasStableTieBreakingAndSafeParameterFailures() throws Exception {
        long first = upload("first.txt", "x"); long second = upload("second.txt", "x");
        jdbc.update("UPDATE knowledge_documents SET create_time='2026-09-28 02:00:00'");
        mvc.perform(get(path()).header("Authorization", token)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.pageSize").value(20));
        mvc.perform(get(path()).param("pageSize", "1").header("Authorization", token)).andExpect(jsonPath("$.data.items[0].id").value(second));
        mvc.perform(get(path()).param("pageSize", "1").param("page", "2").header("Authorization", token)).andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(get(path()).param("pageSize", "100").param("page", "10000").header("Authorization", token)).andExpect(status().isOk());
        for (String value : List.of("0", "101", "bad"))
            mvc.perform(get(path()).param("pageSize", value).header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get(path() + "/0").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get(path() + "/bad").header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test void malformedMultipartShapesAndUntrustedContentFailSafelyWithoutReservations() throws Exception {
        for (var request : List.of(
                multipart(path()).file(file("note.txt", "x")).file(file("again.txt", "y")).param("clientRequestId", UUID.randomUUID().toString()),
                multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                multipart(path()).file(file("note.txt", "x")).param("clientRequestId", "invalid"),
                multipart(path()).file(file("../secret.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()),
                multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).param("endpoint", "untrusted"),
                multipart(path()).file(new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{'x',0})).param("clientRequestId", UUID.randomUUID().toString()))) {
            String body = mvc.perform(request.header("Authorization", token)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("secret.txt", "untrusted", "object_key", "Exception");
        }
        mvc.perform(multipart(path()).file(file("note.pdf", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", token)).andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_requests", Integer.class)).isZero();
        assertThat(storage.writes.get()).isZero();
    }

    @Test void disabledUploadPreservesAuthorizedMetadataAndDeleteAcceptance() throws Exception {
        long id = upload("note.txt", "x"); properties.setEnabled(false);
        mvc.perform(multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", token)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(path()).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get(path() + "/" + id).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(delete(path() + "/" + id).header("Authorization", token)).andExpect(status().isAccepted());
        assertThat(storage.deletes.get()).isZero();
    }

    @Test void fifthConcurrentUploadIsRejectedBeforeItReachesStorage() throws Exception {
        storage.mode = "BLOCK_BEFORE_WRITE"; storage.entered = new CountDownLatch(4);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> service.upload(owner, project,
                    UUID.randomUUID().toString(), file("note.txt", "x")))).toList();
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            mvc.perform(multipart(path()).file(file("note.txt", "x")).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", token))
                    .andExpect(status().isTooManyRequests());
            assertThat(storage.writes.get()).isEqualTo(4);
            storage.release.countDown();
            for (var future : pending) future.get(10, TimeUnit.SECONDS);
        } finally { storage.release.countDown(); }
    }

    @Test void authenticatedOpenApiIncludesMultipartAndTerminalRetentionSemantics() throws Exception {
        String document = mvc.perform(get("/v3/api-docs").header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var operation = json.readTree(document).path("paths").path("/projects/{projectId}/documents").path("post");
        assertThat(operation.path("requestBody").path("content").has("multipart/form-data")).isTrue();
        assertThat(operation.path("description").asText()).contains("24 hours", "409", "STORED");
        assertThat(document).doesNotContain("\"objectKey\"", "\"putToken\"", "\"accessKey\"", "\"secretKey\"");
    }

    long user(String name, boolean normalRole) {
        jdbc.update("INSERT INTO users(username,password) VALUES (?,?)", name, UUID.randomUUID().toString());
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        if (normalRole) roles.assignDefaultRole(id); return id;
    }
    String token(long id, String name, String... userRoles) { return "Bearer " + jwt.generate(new CurrentUser(id, name, List.of(userRoles))); }
    String path() { return "/projects/" + project + "/documents"; }
    MockMultipartFile file(String name, String text) { return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8)); }
    long upload(String filename, String content) throws Exception {
        String result = mvc.perform(multipart(path()).file(file(filename, content)).param("clientRequestId", UUID.randomUUID().toString()).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("id").asLong();
    }
}
