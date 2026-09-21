package com.devmate.ai;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.application.AiMessage;
import com.devmate.ai.config.AiProperties;
import com.devmate.ai.infrastructure.openai.OpenAiResponsesGateway;
import com.devmate.common.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiResponsesGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsStatelessTextOnlyRequestAndAggregatesVisibleOutput() throws Exception {
        Harness harness = harness(properties());
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        harness.server.expect(once(), requestTo("https://unit.test/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(objectMapper.readTree(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes())))
                .andRespond(withSuccess("""
                        {"id":"resp_test","status":"completed","output":[
                          {"type":"reasoning","summary":[]},
                          {"type":"message","role":"assistant","content":[
                            {"type":"output_text","text":"Hello "},
                            {"type":"output_text","text":"world"}]},
                          {"type":"message","role":"assistant","content":[
                            {"type":"refusal","refusal":" — cannot do that"}]}
                        ],"usage":{"input_tokens":12,"output_tokens":7,"total_tokens":19}}
                        """, MediaType.APPLICATION_JSON));

        var result = harness.gateway.chat(new AiChatRequest("developer rules", List.of(
                new AiMessage(AiMessage.Role.USER, "question")), 321));

        assertThat(result.providerRequestId()).isEqualTo("resp_test");
        assertThat(result.content()).isEqualTo("Hello world — cannot do that");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.totalTokens()).isEqualTo(19);
        JsonNode request = captured.get();
        assertThat(request.path("model").asText()).isEqualTo("test-model");
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.has("background")).isFalse();
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(321);
        assertThat(request.path("instructions").asText()).isEqualTo("developer rules");
        assertThat(request.path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(request.has("tools")).isFalse();
        assertThat(request.has("conversation")).isFalse();
        assertThat(request.has("previous_response_id")).isFalse();
        assertThat(request.toString()).doesNotContain("test-only-api-key");
        harness.server.verify();
    }

    @Test
    void rejectsIncompleteEmptyMalformedAndToolOnlyResponses() throws Exception {
        assertInvalid("{\"id\":\"r\",\"status\":\"incomplete\",\"output\":[]}");
        assertInvalid("{\"id\":\"r\",\"status\":\"completed\",\"output\":[]}");
        assertInvalid("not-json");
        assertInvalid("{\"id\":\"r\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"name\":\"unsafe\"}]}");
        assertInvalid("{\"id\":\"r\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"looks safe\"}]},"
                + "{\"type\":\"function_call\",\"name\":\"unsafe\"}]}");
    }

    @Test
    void rejectsOversizedResponseBeforeParsing() throws Exception {
        AiProperties properties = properties();
        properties.setMaxResponseBytes(1024);
        Harness harness = harness(properties);
        harness.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(withSuccess("x".repeat(1025), MediaType.APPLICATION_JSON));
        assertError(harness.gateway, ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void mapsRateLimitServerFailureTimeoutAndNetworkFailure() throws Exception {
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AI_PROVIDER_RATE_LIMITED);
        assertStatus(HttpStatus.UNAUTHORIZED, ErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.AI_PROVIDER_UNAVAILABLE);

        AiProperties limited = properties();
        limited.setMaxResponseBytes(1024);
        Harness oversizedRateLimit = harness(limited);
        oversizedRateLimit.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("x".repeat(1025)));
        assertError(oversizedRateLimit.gateway, ErrorCode.AI_PROVIDER_RATE_LIMITED);

        Harness timeout = harness(properties());
        timeout.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(request -> { throw new SocketTimeoutException("test timeout"); });
        assertError(timeout.gateway, ErrorCode.AI_PROVIDER_TIMEOUT);

        Harness network = harness(properties());
        network.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(request -> { throw new IOException("test network failure"); });
        assertError(network.gateway, ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void validatesEnabledConfigurationButAllowsDisabledStartupWithoutSecrets() throws Exception {
        AiProperties disabled = new AiProperties();
        disabled.afterPropertiesSet();

        AiProperties enabled = new AiProperties();
        enabled.setEnabled(true);
        assertThatThrownBy(enabled::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        enabled.getOpenai().setApiKey("test-only-api-key");
        enabled.getOpenai().setModel("test-model");
        enabled.setGenerationLease(Duration.ofSeconds(30));
        enabled.setReadTimeout(Duration.ofSeconds(30));
        assertThatThrownBy(enabled::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-lease");
    }

    private void assertInvalid(String response) throws Exception {
        Harness harness = harness(properties());
        harness.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertError(harness.gateway, ErrorCode.AI_RESPONSE_INVALID);
    }

    private void assertStatus(HttpStatus status, ErrorCode expected) throws Exception {
        Harness harness = harness(properties());
        harness.server.expect(requestTo("https://unit.test/responses"))
                .andRespond(withStatus(status).body("private upstream detail"));
        assertError(harness.gateway, expected);
    }

    private void assertError(OpenAiResponsesGateway gateway, ErrorCode expected) {
        assertThatThrownBy(() -> gateway.chat(new AiChatRequest("rules", List.of(
                new AiMessage(AiMessage.Role.USER, "question")), 10)))
                .isInstanceOfSatisfying(AiGatewayException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private AiProperties properties() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getOpenai().setBaseUrl("https://unit.test");
        properties.getOpenai().setApiKey("test-only-api-key");
        properties.getOpenai().setModel("test-model");
        properties.afterPropertiesSet();
        return properties;
    }

    private Harness harness(AiProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getOpenai().getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new OpenAiResponsesGateway(builder.build(), objectMapper, properties), server);
    }

    private record Harness(OpenAiResponsesGateway gateway, MockRestServiceServer server) {
    }
}
