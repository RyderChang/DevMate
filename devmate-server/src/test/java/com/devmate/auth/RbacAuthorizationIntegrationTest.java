package com.devmate.auth;

import com.devmate.common.api.Result;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.entity.RoleEntity;
import com.devmate.entity.UserEntity;
import com.devmate.mapper.RoleMapper;
import com.devmate.mapper.UserMapper;
import com.devmate.service.UserRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(RbacAuthorizationIntegrationTest.AuthorityTestController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RbacAuthorizationIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserMapper userMapper;
    @Autowired RoleMapper roleMapper;
    @Autowired UserRoleService userRoleService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanUsers() {
        userMapper.delete(null);
    }

    @Test
    void distinguishesUnauthenticatedUserAndAdminAccess() throws Exception {
        mockMvc.perform(get("/admin/ping")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        register("ordinary");
        String userToken = login("ordinary");
        mockMvc.perform(get("/auth/me").header("Authorization", bearer(userToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/ping").header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));

        register("administrator");
        UserEntity admin = userMapper.findByUsername("administrator");
        RoleEntity adminRole = roleMapper.findByCode("ADMIN");
        userRoleService.assignRole(admin.getId(), adminRole);
        String adminToken = login("administrator");
        mockMvc.perform(get("/admin/ping").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    void resolvesPermissionsFromDatabaseForEveryRequestUsingSameJwt() throws Exception {
        register("permission-user");
        String token = login("permission-user");
        mockMvc.perform(get("/test-authority/user").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test-authority/system").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        jdbc.update("INSERT INTO role_permission(role_id, permission_id) "
                + "SELECT r.id, p.id FROM `role` r JOIN `permission` p WHERE r.code='USER' AND p.code='system'");
        mockMvc.perform(get("/test-authority/system").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id "
                + "JOIN `permission` p ON p.id=rp.permission_id WHERE r.code='USER' AND p.code='user'");
        try {
            mockMvc.perform(get("/test-authority/user").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("INSERT INTO role_permission(role_id, permission_id) SELECT r.id, p.id "
                    + "FROM `role` r JOIN `permission` p WHERE r.code='USER' AND p.code='user'");
            jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id "
                    + "JOIN `permission` p ON p.id=rp.permission_id WHERE r.code='USER' AND p.code='system'");
        }
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

    private String bearer(String token) { return "Bearer " + token; }

    @RestController
    static class AuthorityTestController {
        @GetMapping("/test-authority/user")
        @PreAuthorize("hasAuthority('user')")
        Result<String> user() { return Result.success("user"); }

        @GetMapping("/test-authority/system")
        @PreAuthorize("hasAuthority('system')")
        Result<String> system() { return Result.success("system"); }
    }
}
