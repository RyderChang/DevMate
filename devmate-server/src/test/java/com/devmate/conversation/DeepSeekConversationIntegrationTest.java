package com.devmate.conversation;

import com.devmate.ai.application.AiGateway;
import com.devmate.ai.config.AiProperties;
import com.devmate.ai.infrastructure.deepseek.DeepSeekChatGateway;
import com.devmate.database.MySqlIntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(DeepSeekConversationIntegrationTest.HttpStubConfiguration.class)
@TestPropertySource(properties = {"devmate.ai.enabled=true", "devmate.ai.provider=deepseek",
        "devmate.ai.deepseek.model=deepseek-flash"})
class DeepSeekConversationIntegrationTest extends MySqlIntegrationTestBase {
    private static final String KEY = UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Harness harness;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("devmate.ai.deepseek.api-key", () -> KEY);
    }

    @BeforeEach
    void before() { cleanup(); harness.server.reset(); }

    @AfterEach
    void after() { harness.server.verify(); cleanup(); }

    @Test
    void persistsDeepSeekUsageAndReplaysWithoutCallingProviderTwice() throws Exception {
        var owner = workspace("deepseek-owner");
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(request -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse())
                .andRespond(withSuccess("""
                        {"id":"chat-integration","object":"chat.completion","model":"deepseek-flash",
                        "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                        "content":"合成中文答复","reasoning_content":"private hidden reasoning"}}],
                        "usage":{"prompt_tokens":8,"completion_tokens":5,"total_tokens":13}}
                        """, MediaType.APPLICATION_JSON));
        String body = mapper.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID().toString(), "content", "合成测试问题"));
        String first = send(owner, owner.token, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invocation.provider").value("deepseek"))
                .andExpect(jsonPath("$.data.invocation.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.data.invocation.inputTokens").value(8))
                .andExpect(jsonPath("$.data.invocation.outputTokens").value(5))
                .andExpect(jsonPath("$.data.invocation.totalTokens").value(13))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("合成中文答复"))
                .andReturn().getResponse().getContentAsString();
        String second = send(owner, owner.token, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(second).path("data")).isEqualTo(mapper.readTree(first).path("data"));
        assertThat(first).doesNotContain("private hidden reasoning", KEY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_invocations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT provider_request_id FROM ai_invocations", String.class)).isEqualTo("chat-integration");
        assertThat(jdbc.queryForList("SELECT content FROM conversation_messages", String.class))
                .allSatisfy(content -> assertThat(content).doesNotContain("private hidden reasoning", KEY));
    }

    @Test
    void deniesOtherOwnersBeforeHttpAndLeavesNoInvocation() throws Exception {
        var owner = workspace("deepseek-owner");
        var outsider = workspace("deepseek-outsider");
        String body = mapper.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID().toString(), "content", "question"));
        send(owner, outsider.token, body).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_invocations", Integer.class)).isZero();
    }

    @Test
    void recordsSafeRateLimitAndReleasesLeaseWithoutRepeatingFailedUuid() throws Exception {
        var owner = workspace("deepseek-owner");
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private provider diagnostic"));
        String body = mapper.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID().toString(), "content", "question"));
        String response = send(owner, owner.token, body).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        send(owner, owner.token, body).andExpect(status().isServiceUnavailable());
        assertThat(response).doesNotContain("private provider diagnostic", KEY);
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_invocations", String.class)).isEqualTo("AI_PROVIDER_RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT generation_state FROM conversations", String.class)).isEqualTo("IDLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_invocations", Integer.class)).isOne();
    }

    private org.springframework.test.web.servlet.ResultActions send(Workspace workspace, String token, String body) throws Exception {
        return mvc.perform(post("/projects/{projectId}/conversations/{conversationId}/messages", workspace.project, workspace.conversation)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Workspace workspace(String username) throws Exception {
        String password = UUID.randomUUID().toString();
        String credentials = mapper.writeValueAsString(Map.of("username", username, "password", password));
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isOk());
        String login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("data").path("token").asText();
        String project = mvc.perform(post("/projects").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Synthetic\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long projectId = mapper.readTree(project).path("data").path("id").asLong();
        String conversation = mvc.perform(post("/projects/{projectId}/conversations", projectId)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Synthetic\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Workspace(token, projectId, mapper.readTree(conversation).path("data").path("id").asLong());
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ai_invocations");
        jdbc.update("DELETE FROM conversation_messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM users");
    }

    private record Workspace(String token, long project, long conversation) {}
    private record Harness(DeepSeekChatGateway gateway, MockRestServiceServer server) {}

    @TestConfiguration
    static class HttpStubConfiguration {
        @Bean Harness harness(AiProperties properties, ObjectMapper mapper) {
            var builder = RestClient.builder().baseUrl(properties.getDeepseek().getBaseUrl());
            var server = MockRestServiceServer.bindTo(builder).build();
            return new Harness(new DeepSeekChatGateway(builder.build(), mapper, properties), server);
        }
        @Bean @Primary AiGateway httpStubGateway(Harness harness) { return harness.gateway; }
    }
}
