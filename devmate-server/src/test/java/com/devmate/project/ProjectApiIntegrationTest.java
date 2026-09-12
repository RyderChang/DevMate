package com.devmate.project;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.entity.RoleEntity;
import com.devmate.entity.UserEntity;
import com.devmate.mapper.RoleMapper;
import com.devmate.mapper.UserMapper;
import com.devmate.service.UserRoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectApiIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper userMapper;
    @Autowired RoleMapper roleMapper;
    @Autowired UserRoleService userRoleService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM projects");
        userMapper.delete(null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM projects");
        userMapper.delete(null);
    }

    @Test
    void requiresAuthenticationAndUserAuthority() throws Exception {
        mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anonymous\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/projects").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));

        register("system-only");
        UserEntity user = userMapper.findByUsername("system-only");
        jdbc.update("DELETE FROM user_role WHERE user_id=?", user.getId());
        RoleEntity adminRole = roleMapper.findByCode("ADMIN");
        userRoleService.assignRole(user.getId(), adminRole);
        String token = login("system-only");

        mockMvc.perform(get("/projects").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void performsCrudAndHidesOtherUsersProjects() throws Exception {
        register("owner");
        register("outsider");
        String ownerToken = login("owner");
        String outsiderToken = login("outsider");
        long projectId = create(ownerToken, "  Workspace  ", "  Description  ");

        mockMvc.perform(get("/projects").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(projectId))
                .andExpect(jsonPath("$.data.items[0].name").value("Workspace"));
        mockMvc.perform(get("/projects").header("Authorization", bearer(outsiderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get("/projects/{id}", projectId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Workspace"))
                .andExpect(jsonPath("$.data.createTime").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist());
        expectNotFound(get("/projects/{id}", projectId), outsiderToken);
        expectNotFound(put("/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hacked\"}"), outsiderToken);
        expectNotFound(delete("/projects/{id}", projectId), outsiderToken);
        assertThat(jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, projectId))
                .isEqualTo("Workspace");
        assertThat(jdbc.queryForObject("SELECT deleted FROM projects WHERE id=?", Integer.class, projectId))
                .isZero();

        Long ownerId = userMapper.findByUsername("owner").getId();
        mockMvc.perform(put("/projects/{id}", projectId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Still owned\",\"ownerUserId\":999,\"deleted\":true,"
                                + "\"id\":999,\"createTime\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Still owned"));
        var protectedFields = jdbc.queryForMap(
                "SELECT owner_user_id, deleted, create_time FROM projects WHERE id=?", projectId);
        assertThat(protectedFields.get("owner_user_id")).isEqualTo(ownerId);
        assertThat(protectedFields.get("deleted")).isEqualTo(false);
        assertThat(protectedFields.get("create_time").toString()).doesNotStartWith("2000-01-01");

        mockMvc.perform(put("/projects/{id}", projectId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"description\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed"));
        mockMvc.perform(delete("/projects/{id}", projectId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/projects").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        expectNotFound(get("/projects/{id}", projectId), ownerToken);
        expectNotFound(put("/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cannot update deleted project\"}"), ownerToken);
        expectNotFound(delete("/projects/{id}", projectId), ownerToken);
    }

    @Test
    void validatesNormalizedInputPaginationAndMassAssignment() throws Exception {
        register("validation-user");
        String token = login("validation-user");
        long projectId = create(token, "  " + "x".repeat(100) + "  ", "   ");
        Long actualOwner = jdbc.queryForObject("SELECT owner_user_id FROM projects WHERE id=?", Long.class, projectId);
        Long userId = userMapper.findByUsername("validation-user").getId();
        assertThat(actualOwner).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT description FROM projects WHERE id=?", String.class, projectId)).isNull();

        String massAssignmentResponse = mockMvc.perform(post("/projects").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Safe\",\"ownerUserId\":999,\"deleted\":true,\"id\":999,"
                                + "\"createTime\":\"2000-01-01T00:00:00Z\","
                                + "\"updateTime\":\"2000-01-01T00:00:00Z\","
                                + "\"deleteTime\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long safeProjectId = objectMapper.readTree(massAssignmentResponse).path("data").path("id").asLong();
        var persisted = jdbc.queryForMap("SELECT owner_user_id, deleted, create_time, update_time, delete_time "
                + "FROM projects WHERE id=?", safeProjectId);
        assertThat(safeProjectId).isNotEqualTo(999L);
        assertThat(persisted.get("owner_user_id")).isEqualTo(userId);
        assertThat(persisted.get("deleted")).isEqualTo(false);
        assertThat(persisted.get("delete_time")).isNull();
        assertThat(persisted.get("create_time").toString()).doesNotStartWith("2000-01-01");
        assertThat(persisted.get("update_time").toString()).doesNotStartWith("2000-01-01");
        assertBadRequest(post("/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(101) + "\"}"), token);
        assertBadRequest(post("/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"), token);
        assertBadRequest(post("/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\u3000\u3000\"}"), token);
        assertBadRequest(post("/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Valid\",\"description\":\"" + "x".repeat(1001) + "\"}"), token);
        assertBadRequest(put("/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Missing name\"}"), token);
        assertBadRequest(get("/projects?page=0"), token);
        assertBadRequest(get("/projects?page=-1"), token);
        assertBadRequest(get("/projects?page=10001"), token);
        assertBadRequest(get("/projects?pageSize=0"), token);
        assertBadRequest(get("/projects?pageSize=-1"), token);
        assertBadRequest(get("/projects?pageSize=101"), token);
        mockMvc.perform(get("/projects?page=10000&pageSize=100")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void userAndAdminCombinationStillCannotBypassOwnership() throws Exception {
        register("admin-owner");
        register("different-owner");
        UserEntity admin = userMapper.findByUsername("admin-owner");
        userRoleService.assignRole(admin.getId(), roleMapper.findByCode("ADMIN"));
        String adminToken = login("admin-owner");
        long otherProject = create(login("different-owner"), "Private", null);

        expectNotFound(get("/projects/{id}", otherProject), adminToken);
    }

    @Test
    void authenticatedOpenApiDocumentsProjectOperationsValidationAndBearerAuth() throws Exception {
        register("openapi-user");
        String token = login("openapi-user");
        String response = mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(response).path("paths");
        assertThat(paths.path("/projects").has("get")).isTrue();
        assertThat(paths.path("/projects").has("post")).isTrue();
        assertThat(paths.path("/projects/{projectId}").has("get")).isTrue();
        assertThat(paths.path("/projects/{projectId}").has("put")).isTrue();
        assertThat(paths.path("/projects/{projectId}").has("delete")).isTrue();

        JsonNode document = objectMapper.readTree(response);
        JsonNode bearerScheme = document.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(bearerScheme.path("type").asText()).isEqualTo("http");
        assertThat(bearerScheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(bearerScheme.path("bearerFormat").asText()).isEqualTo("JWT");
        assertBearerSecurity(paths.path("/projects").path("get"));
        assertBearerSecurity(paths.path("/projects").path("post"));
        assertBearerSecurity(paths.path("/projects/{projectId}").path("get"));
        assertBearerSecurity(paths.path("/projects/{projectId}").path("put"));
        assertBearerSecurity(paths.path("/projects/{projectId}").path("delete"));

        JsonNode createSchema = document.path("components").path("schemas").path("CreateProjectRequest");
        assertThat(StreamSupport.stream(createSchema.path("required").spliterator(), false)
                .map(JsonNode::asText)).contains("name");
        assertThat(createSchema.path("properties").path("name").path("minLength").asInt()).isOne();
        assertThat(createSchema.path("properties").path("name").path("maxLength").asInt()).isEqualTo(100);
        assertThat(createSchema.path("properties").path("description").path("maxLength").asInt())
                .isEqualTo(1000);
        JsonNode responseSchema = document.path("components").path("schemas").path("ProjectResponse");
        assertThat(responseSchema.path("properties").has("ownerUserId")).isFalse();
        assertThat(responseSchema.path("properties").has("deleted")).isFalse();
        assertThat(responseSchema.path("properties").has("deleteTime")).isFalse();

        JsonNode listOperation = paths.path("/projects").path("get");
        assertParameter(listOperation, "page", 1, 10_000, 1);
        assertParameter(listOperation, "pageSize", 1, 100, 20);
    }

    private void assertBearerSecurity(JsonNode operation) {
        assertThat(StreamSupport.stream(operation.path("security").spliterator(), false)
                .anyMatch(requirement -> requirement.has("bearerAuth"))).isTrue();
    }

    private void assertParameter(JsonNode operation, String name, int minimum, int maximum, int defaultValue) {
        JsonNode parameter = StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(candidate -> name.equals(candidate.path("name").asText()))
                .findFirst().orElseThrow();
        JsonNode schema = parameter.path("schema");
        assertThat(schema.path("minimum").asInt()).isEqualTo(minimum);
        assertThat(schema.path("maximum").asInt()).isEqualTo(maximum);
        assertThat(schema.path("default").asInt()).isEqualTo(defaultValue);
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long create(String token, String name, String description) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "name", name,
                "description", description == null ? "" : description));
        String response = mockMvc.perform(post("/projects")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private void expectNotFound(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                String token) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Project not found"));
    }

    private void assertBadRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  String token) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
