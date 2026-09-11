package com.devmate.auth;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.entity.UserEntity;
import com.devmate.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUsers() {
        userMapper.delete(null);
    }

    @Test
    void registersUserWithBcryptPasswordAndNoPasswordResponse() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" alice \",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("alice"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password123"))));

        UserEntity saved = userMapper.findByUsername("alice");
        assertThat(saved.getPassword()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPassword())).isTrue();
        assertThat(saved.getCreateTime()).isNotNull();
        assertThat(saved.getUpdateTime()).isNotNull();
    }

    @Test
    void rejectsInvalidAndDuplicateRegistration() throws Exception {
        register("bob", "password123");
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"password456\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-user\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logsInAndUsesValidTokenForCurrentUser() throws Exception {
        register("carol", "password123");
        String response = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.user.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("token").asText();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("carol"));
    }

    @Test
    void rejectsBadCredentialsMissingTokenAndInvalidToken() throws Exception {
        register("dave", "password123");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dave\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/not-whitelisted")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }
}










