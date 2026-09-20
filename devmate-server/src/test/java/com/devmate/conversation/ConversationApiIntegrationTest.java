package com.devmate.conversation;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.common.api.ErrorCode;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ConversationApiIntegrationTest.StubConfiguration.class)
class ConversationApiIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper userMapper;
    @Autowired StubAiGateway gateway;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        gateway.reset();
    }

    @AfterEach
    void cleanUp() {
        cleanDatabase();
    }

    @Test
    void createsListsAndHidesConversationsAcrossOwnersAndProjects() throws Exception {
        register("conversation-owner");
        register("conversation-outsider");
        String ownerToken = login("conversation-owner");
        String outsiderToken = login("conversation-outsider");
        long projectId = createProject(ownerToken, "Workspace", "Sensitive project description");
        long conversationId = createConversation(ownerToken, projectId, "  Architecture  ");

        mockMvc.perform(get("/projects/{projectId}/conversations", projectId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Architecture"))
                .andExpect(jsonPath("$.data.items[0].generationState").value("IDLE"));
        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}", projectId, conversationId)
                        .header("Authorization", bearer(outsiderToken)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}", projectId + 999, conversationId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        long defaultConversation = createConversation(ownerToken, projectId, "   ");
        assertThat(jdbc.queryForObject("SELECT title FROM conversations WHERE id=?", String.class,
                defaultConversation)).isEqualTo("New conversation");
    }

    @Test
    void generatesOutsideTransactionPersistsAuditDataAndReturnsIdempotentResult() throws Exception {
        register("chat-owner");
        String token = login("chat-owner");
        long projectId = createProject(token, "Workspace", "Project context");
        long conversationId = createConversation(token, projectId, "Chat");
        String requestId = "5abf96bb-58d8-4f18-97f6-70d877cf6257";
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "clientRequestId", requestId, "content", "  Explain the boundaries.  "));

        String first = mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        projectId, conversationId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.content").value("Explain the boundaries."))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("Stubbed answer"))
                .andExpect(jsonPath("$.data.invocation.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.invocation.provider").value("openai"))
                .andExpect(jsonPath("$.data.invocation.model").value("test-model"))
                .andExpect(jsonPath("$.data.invocation.totalTokens").value(13))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        projectId, conversationId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).path("data"))
                .isEqualTo(objectMapper.readTree(first).path("data"));
        assertThat(gateway.calls.get()).isOne();
        assertThat(gateway.calledInsideTransaction.get()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_invocations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT prompt_template_version FROM ai_invocations", String.class))
                .isEqualTo("project-chat-v1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_messages", Integer.class)).isEqualTo(2);
        assertThat(gateway.lastRequest.get().instructions()).doesNotContain("Workspace", "Project context");
        assertThat(gateway.lastRequest.get().messages().get(0).content())
                .contains("untrusted_project_data", "Workspace", "Project context");

        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}/messages",
                        projectId, conversationId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.items[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.items[1].sequenceNo").value(2));
    }

    @Test
    void recordsSafeFailureReleasesLeaseAndDoesNotRetrySameRequestId() throws Exception {
        register("failure-owner");
        String token = login("failure-owner");
        long projectId = createProject(token, "Workspace", null);
        long conversationId = createConversation(token, projectId, "Failures");
        gateway.failure.set(ErrorCode.AI_PROVIDER_RATE_LIMITED);
        String requestId = "811a6c42-a61c-42db-a097-1f5f41a11dc0";
        String body = "{\"clientRequestId\":\"" + requestId + "\",\"content\":\"hello\"}";

        expectError(post("/projects/{projectId}/conversations/{conversationId}/messages",
                projectId, conversationId).contentType(MediaType.APPLICATION_JSON).content(body), token, 503);
        expectError(post("/projects/{projectId}/conversations/{conversationId}/messages",
                projectId, conversationId).contentType(MediaType.APPLICATION_JSON).content(body), token, 503);

        assertThat(gateway.calls.get()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_invocations", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_invocations", String.class))
                .isEqualTo("AI_PROVIDER_RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT generation_state FROM conversations", String.class)).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT generation_started_at FROM conversations", Object.class)).isNull();
    }

    @Test
    void completesAndReleasesLeaseIfProjectIsDeletedDuringRemoteCall() throws Exception {
        register("delete-race-owner");
        String token = login("delete-race-owner");
        long projectId = createProject(token, "Workspace", null);
        long conversationId = createConversation(token, projectId, "Delete race");
        gateway.beforeReturn.set(() -> jdbc.update("UPDATE projects SET deleted=1 WHERE id=?", projectId));

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        projectId, conversationId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"clientRequestId\":\"16c91164-312b-4b1a-9564-186ad32d540b\","
                                        + "\"content\":\"finish consistently\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM ai_invocations", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT generation_state FROM conversations", String.class))
                .isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT generation_started_at FROM conversations", Object.class)).isNull();
    }

    @Test
    void enforcesAuthenticationValidationDisabledStateAndActiveLease() throws Exception {
        mockMvc.perform(get("/projects/1/conversations")).andExpect(status().isUnauthorized());
        register("guard-owner");
        String token = login("guard-owner");
        long projectId = createProject(token, "Workspace", null);
        long conversationId = createConversation(token, projectId, "Guards");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id "
                + "JOIN permission p ON p.id=rp.permission_id WHERE r.code='USER' AND p.code='user'");
        try {
            mockMvc.perform(get("/projects/{projectId}/conversations", projectId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        } finally {
            jdbc.update("INSERT INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM `role` r "
                    + "JOIN permission p ON p.code='user' WHERE r.code='USER'");
        }
        assertBadRequest(post("/projects/{projectId}/conversations/{conversationId}/messages",
                projectId, conversationId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"bad\",\"content\":\"hello\"}"), token);
        assertBadRequest(get("/projects/{projectId}/conversations?pageSize=101", projectId), token);

        gateway.enabled.set(false);
        expectError(post("/projects/{projectId}/conversations/{conversationId}/messages",
                projectId, conversationId).contentType(MediaType.APPLICATION_JSON).content(
                "{\"clientRequestId\":\"6ac90474-d684-4200-9d4b-70ef8ecf51c7\",\"content\":\"hello\"}"), token, 503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_messages", Integer.class)).isZero();
        mockMvc.perform(get("/projects/{projectId}/conversations/{conversationId}", projectId, conversationId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(conversationId));
        gateway.enabled.set(true);
        jdbc.update("UPDATE conversations SET generation_state='GENERATING',generation_started_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=?", conversationId);
        expectError(post("/projects/{projectId}/conversations/{conversationId}/messages",
                projectId, conversationId).contentType(MediaType.APPLICATION_JSON).content(
                "{\"clientRequestId\":\"08db7006-1606-482a-8bc4-e348599ad80a\",\"content\":\"hello\"}"), token, 409);
        assertThat(gateway.calls.get()).isZero();
    }

    @Test
    void expiresAbandonedLeaseBeforeStartingNewGeneration() throws Exception {
        register("lease-owner");
        String token = login("lease-owner");
        long projectId = createProject(token, "Workspace", null);
        long conversationId = createConversation(token, projectId, "Lease recovery");
        Long ownerId = userMapper.findByUsername("lease-owner").getId();
        jdbc.update("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content) "
                + "VALUES (?,1,'USER','abandoned')", conversationId);
        Long abandonedMessageId = jdbc.queryForObject(
                "SELECT id FROM conversation_messages WHERE conversation_id=?", Long.class, conversationId);
        String abandonedRequest = "9a63c16a-0954-46f6-8c8b-6293f6447169";
        jdbc.update("INSERT INTO ai_invocations(conversation_id,client_request_id,user_message_id,provider,model,"
                        + "prompt_template_version,status,started_at) VALUES (?,?,?,?,?,?,'PENDING',"
                        + "CURRENT_TIMESTAMP(6)-INTERVAL 10 MINUTE)", conversationId, abandonedRequest,
                abandonedMessageId, "openai", "test-model", "project-chat-v1");
        jdbc.update("UPDATE conversations SET generation_state='GENERATING',"
                + "generation_started_at=CURRENT_TIMESTAMP(6)-INTERVAL 10 MINUTE WHERE id=? "
                + "AND owner_user_id=?", conversationId, ownerId);

        mockMvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages",
                        projectId, conversationId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"clientRequestId\":\"56ee3a5b-4af6-49da-8768-dcc1b7d6e96d\","
                                        + "\"content\":\"new request\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM ai_invocations WHERE client_request_id=?",
                String.class, abandonedRequest)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_invocations WHERE client_request_id=?",
                String.class, abandonedRequest)).isEqualTo("AI_REQUEST_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT generation_state FROM conversations WHERE id=?",
                String.class, conversationId)).isEqualTo("IDLE");
        assertThat(gateway.calls.get()).isOne();
    }

    @Test
    void documentsConversationEndpointsWithoutExposingInternalInvocationFields() throws Exception {
        register("conversation-openapi");
        String token = login("conversation-openapi");
        String response = mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(response);
        JsonNode paths = document.path("paths");
        assertThat(paths.path("/projects/{projectId}/conversations").has("post")).isTrue();
        assertThat(paths.path("/projects/{projectId}/conversations").has("get")).isTrue();
        assertThat(paths.path("/projects/{projectId}/conversations/{conversationId}").has("get")).isTrue();
        assertThat(paths.path("/projects/{projectId}/conversations/{conversationId}/messages").has("get")).isTrue();
        assertThat(paths.path("/projects/{projectId}/conversations/{conversationId}/messages").has("post")).isTrue();
        JsonNode invocation = document.path("components").path("schemas").path("InvocationSummary")
                .path("properties");
        assertThat(invocation.has("providerRequestId")).isFalse();
        assertThat(invocation.has("errorCode")).isFalse();
        assertThat(invocation.has("prompt")).isFalse();
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM ai_invocations");
        jdbc.update("DELETE FROM conversation_messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM projects");
        userMapper.delete(null);
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

    private long createProject(String token, String name, String description) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("description", description);
        String response = mockMvc.perform(post("/projects").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private long createConversation(String token, long projectId, String title) throws Exception {
        String response = mockMvc.perform(post("/projects/{projectId}/conversations", projectId)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("title", title))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private void expectError(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             String token, int code) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
    }

    private void assertBadRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  String token) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        @Primary
        StubAiGateway stubAiGateway() {
            return new StubAiGateway();
        }
    }

    static final class StubAiGateway implements AiGateway {
        final AtomicReference<Boolean> enabled = new AtomicReference<>(true);
        final AtomicReference<ErrorCode> failure = new AtomicReference<>();
        final AtomicReference<AiChatRequest> lastRequest = new AtomicReference<>();
        final AtomicReference<Runnable> beforeReturn = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Boolean> calledInsideTransaction = new AtomicReference<>(false);

        void reset() {
            enabled.set(true);
            failure.set(null);
            lastRequest.set(null);
            beforeReturn.set(null);
            calls.set(0);
            calledInsideTransaction.set(false);
        }

        @Override public boolean enabled() { return enabled.get(); }
        @Override public String provider() { return "openai"; }
        @Override public String model() { return "test-model"; }

        @Override
        public AiChatResult chat(AiChatRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            calledInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            if (failure.get() != null) {
                throw new AiGatewayException(failure.get());
            }
            if (beforeReturn.get() != null) {
                beforeReturn.get().run();
            }
            return new AiChatResult("resp_stub", "Stubbed answer", 8, 5, 13, 12L);
        }
    }
}
