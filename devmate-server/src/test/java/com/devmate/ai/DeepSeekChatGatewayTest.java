package com.devmate.ai;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.application.AiMessage;
import com.devmate.ai.config.AiProperties;
import com.devmate.ai.infrastructure.deepseek.DeepSeekChatGateway;
import com.devmate.common.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekChatGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String RESPONSE = """
            {"id":"chat-test","object":"chat.completion","model":"deepseek-flash",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                "content":"中文答复 😀","reasoning_content":"hidden private reasoning"}}],
             "usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,
                "prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":7}}
            """;

    @Test
    void sendsTextHistoryWithSystemRulesAndDisablesThinkingWithoutExposingIt() throws Exception {
        var harness = harness(properties());
        var captured = new AtomicReference<JsonNode>();
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + harness.properties.getDeepseek().getApiKey()))
                .andExpect(request -> captured.set(mapper.readTree(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes())))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var result = harness.gateway.chat(new AiChatRequest("system rules", List.of(
                new AiMessage(AiMessage.Role.USER, "earlier question"),
                new AiMessage(AiMessage.Role.ASSISTANT, "earlier answer"),
                new AiMessage(AiMessage.Role.USER, "新问题")), 321));
        assertThat(result.content()).isEqualTo("中文答复 😀").doesNotContain("hidden private reasoning");
        assertThat(result.providerRequestId()).isEqualTo("chat-test");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.totalTokens()).isEqualTo(19);
        assertThat(harness.gateway.provider()).isEqualTo("deepseek");
        JsonNode body = captured.get();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(321);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("system rules");
        assertThat(body.path("messages").get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.toString()).doesNotContain(harness.properties.getDeepseek().getApiKey(),
                "reasoning_content", "tools", "previous_response_id", "conversation");
        harness.server.verify();
    }

    @Test
    void rejectsIncompleteFilteredAbortedAndToolResponsesEvenWithVisibleContent() throws Exception {
        for (String finish : List.of("length", "content_filter", "tool_calls", "insufficient_system_resource", "aborted")) {
            ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
            ((ObjectNode) root.path("choices").get(0)).put("finish_reason", finish);
            assertInvalid(root.toString());
        }
        ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) root.path("choices").get(0).path("message")).putArray("tool_calls").addObject().put("id", "tool");
        assertInvalid(root.toString());
        root = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) root.path("choices").get(0).path("message")).putObject("function_call").put("name", "unsafe");
        assertInvalid(root.toString());
    }

    @Test
    void rejectsMissingAmbiguousAndNonTextOutput() throws Exception {
        assertInvalid("not-json");
        assertInvalid("null");
        for (String field : List.of("id", "object", "model", "choices")) {
            ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
            root.remove(field);
            assertInvalid(root.toString());
        }
        ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
        root.putArray("choices");
        assertInvalid(root.toString());
        root = (ObjectNode) mapper.readTree(RESPONSE);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("choices")).add(root.path("choices").get(0).deepCopy());
        assertInvalid(root.toString());
        for (String field : List.of("role", "content")) {
            root = (ObjectNode) mapper.readTree(RESPONSE);
            ((ObjectNode) root.path("choices").get(0).path("message")).remove(field);
            assertInvalid(root.toString());
        }
        root = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) root.path("choices").get(0).path("message")).putArray("content").add("array text");
        assertInvalid(root.toString());
    }

    @Test
    void requiresIntegralNonNegativeAndConsistentUsageInsteadOfRecordingZero() throws Exception {
        for (String field : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            for (JsonNode value : List.of(mapper.readTree("-1"), mapper.readTree("1.5"), mapper.readTree("2147483648"), mapper.readTree("null"))) {
                ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
                ((ObjectNode) root.path("usage")).set(field, value);
                assertInvalid(root.toString());
            }
        }
        ObjectNode root = (ObjectNode) mapper.readTree(RESPONSE);
        root.remove("usage");
        assertInvalid(root.toString());
        root = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) root.path("usage")).put("total_tokens", 20);
        assertInvalid(root.toString());
    }

    @Test
    void boundsResponseBytesAndRejectsMalformedUtf8() {
        var properties = properties();
        properties.setMaxResponseBytes(1024);
        var harness = harness(properties);
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("x".repeat(1025), MediaType.APPLICATION_JSON));
        assertError(harness, ErrorCode.AI_RESPONSE_INVALID);
        harness = harness(properties());
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(new byte[]{(byte) 0xc3, 0x28}, MediaType.APPLICATION_JSON));
        assertError(harness, ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void mapsUpstreamFailuresWithoutReadingPrivateDiagnosticsOrRetrying() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.PAYMENT_REQUIRED,
                HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_REQUEST)) {
            var harness = harness(properties());
            harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withStatus(status).body("private upstream diagnostic".repeat(100_000)));
            assertError(harness, status == HttpStatus.TOO_MANY_REQUESTS ? ErrorCode.AI_PROVIDER_RATE_LIMITED
                    : status == HttpStatus.BAD_REQUEST ? ErrorCode.AI_RESPONSE_INVALID : ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    @Test
    void classifiesOnlyFixedResponseIssuesWithoutRetainingProviderBody() throws Exception {
        var http = harness(properties());
        http.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("private upstream diagnostic"));
        assertIssue(http, AiGatewayException.ResponseIssue.UPSTREAM_STATUS);

        ObjectNode stopped = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) stopped.path("choices").get(0)).put("finish_reason", "length");
        var finish = harness(properties());
        finish.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(stopped.toString(), MediaType.APPLICATION_JSON));
        assertIssue(finish, AiGatewayException.ResponseIssue.FINISH_REASON);

        var malformed = harness(properties());
        malformed.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("private malformed JSON", MediaType.APPLICATION_JSON));
        assertIssue(malformed, AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE);

        ObjectNode missingContent = (ObjectNode) mapper.readTree(RESPONSE);
        ((ObjectNode) missingContent.path("choices").get(0).path("message")).putNull("content");
        var content = harness(properties());
        content.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(missingContent.toString(), MediaType.APPLICATION_JSON));
        assertIssue(content, AiGatewayException.ResponseIssue.MESSAGE_CONTENT);
    }

    private void assertIssue(Harness harness, AiGatewayException.ResponseIssue expected) {
        assertThatThrownBy(() -> harness.gateway.chat(new AiChatRequest("rules", List.of(
                new AiMessage(AiMessage.Role.USER, "question")), 10)))
                .isInstanceOfSatisfying(AiGatewayException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
                    assertThat(error.getResponseIssue()).isEqualTo(expected);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID.getMessage());
                    assertThat(error.getCause()).isNull();
                });
        harness.server.verify();
    }

    @Test
    void distinguishesTimeoutAndNetworkFailureWithoutAttachingSensitiveCauses() {
        var harness = harness(properties());
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(request -> { throw new SocketTimeoutException("private timeout diagnostic"); });
        assertError(harness, ErrorCode.AI_PROVIDER_TIMEOUT);
        harness = harness(properties());
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(request -> { throw new IOException("private network diagnostic"); });
        assertError(harness, ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void rejectsOutputLimitAboveConfigurationBeforeAnyHttpRequest() {
        var harness = harness(properties());
        assertThatThrownBy(() -> harness.gateway.chat(new AiChatRequest("rules", List.of(
                new AiMessage(AiMessage.Role.USER, "question")), 1025)))
                .isInstanceOf(AiGatewayException.class);
        harness.server.verify();
    }

    private void assertInvalid(String json) {
        var harness = harness(properties());
        harness.server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertError(harness, ErrorCode.AI_RESPONSE_INVALID);
    }

    private void assertError(Harness harness, ErrorCode expected) {
        assertThatThrownBy(() -> harness.gateway.chat(new AiChatRequest("rules", List.of(
                new AiMessage(AiMessage.Role.USER, "question")), 10)))
                .isInstanceOfSatisfying(AiGatewayException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(expected);
                    assertThat(error.getMessage()).isEqualTo(expected.getMessage());
                    assertThat(error.getCause()).isNull();
                });
        harness.server.verify();
    }

    private AiProperties properties() {
        var properties = new AiProperties();
        properties.setEnabled(true);
        properties.setProvider("deepseek");
        properties.getDeepseek().setApiKey(UUID.randomUUID().toString());
        properties.getDeepseek().setModel("deepseek-flash");
        properties.afterPropertiesSet();
        return properties;
    }

    private Harness harness(AiProperties properties) {
        var builder = RestClient.builder().baseUrl(properties.getDeepseek().getBaseUrl());
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new DeepSeekChatGateway(builder.build(), mapper, properties), server, properties);
    }

    private record Harness(DeepSeekChatGateway gateway, MockRestServiceServer server, AiProperties properties) {}
}
