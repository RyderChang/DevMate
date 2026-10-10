package com.devmate.ai.infrastructure.deepseek;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Stateless, text-only DeepSeek chat. Hidden reasoning and tool calls never become visible output. */
public final class DeepSeekChatGateway implements AiGateway {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final AiProperties properties;

    public DeepSeekChatGateway(RestClient client, ObjectMapper mapper, AiProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override public boolean enabled() { return true; }
    @Override public String provider() { return "deepseek"; }
    @Override public String model() { return properties.getDeepseek().getModel(); }

    @Override
    public AiChatResult chat(AiChatRequest request) {
        if (request.maxOutputTokens() > properties.getMaxOutputTokens()) throw invalid(AiGatewayException.ResponseIssue.UNCLASSIFIED);
        long started = System.nanoTime();
        try {
            return client.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDeepseek().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(requestBody(request))
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) throw new AiGatewayException(ErrorCode.AI_PROVIDER_RATE_LIMITED);
                        if (status < 200 || status >= 300) {
                            boolean unavailable = status >= 500 || status == 401 || status == 402 || status == 403;
                            throw unavailable ? new AiGatewayException(ErrorCode.AI_PROVIDER_UNAVAILABLE)
                                    : invalid(AiGatewayException.ResponseIssue.UPSTREAM_STATUS);
                        }
                        return parse(readLimited(response.getBody()), elapsedMillis(started));
                    });
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AiGatewayException(hasTimeout(exception)
                    ? ErrorCode.AI_PROVIDER_TIMEOUT : ErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw new AiGatewayException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private Map<String, Object> requestBody(AiChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("stream", false);
        body.put("thinking", Map.of("type", "disabled"));
        body.put("max_tokens", request.maxOutputTokens());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.instructions()));
        request.messages().forEach(message -> messages.add(Map.of(
                "role", message.role().apiValue(), "content", message.content())));
        body.put("messages", messages);
        return body;
    }

    private byte[] readLimited(InputStream input) {
        try (input) {
            var buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (true) {
                com.devmate.ai.application.CallBudget.cap(java.time.Duration.ofSeconds(120));
                int count = input.read(chunk);
                if (count == -1) break;
                if (count > properties.getMaxResponseBytes() - buffer.size()) throw invalid(AiGatewayException.ResponseIssue.RESPONSE_SIZE);
                buffer.write(chunk, 0, count);
            }
            com.devmate.ai.application.CallBudget.cap(java.time.Duration.ofSeconds(120));
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > properties.getMaxResponseBytes()) throw invalid(AiGatewayException.ResponseIssue.RESPONSE_SIZE);
            return bytes;
        } catch (IOException exception) {
            throw new AiGatewayException(hasTimeout(exception)
                    ? ErrorCode.AI_PROVIDER_TIMEOUT : ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private AiChatResult parse(byte[] bytes, long durationMs) {
        try {
            String json;
            try { json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (java.nio.charset.CharacterCodingException error) { throw invalid(AiGatewayException.ResponseIssue.RESPONSE_ENCODING); }
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || !"chat.completion".equals(text(root, "object"))) throw invalid(AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE);
            String id = text(root, "id");
            String responseModel = text(root, "model");
            JsonNode choices = root.get("choices");
            if (id == null || id.isBlank() || id.length() > 255 || responseModel == null || responseModel.isBlank()
                    || responseModel.length() > 100 || choices == null || !choices.isArray() || choices.size() != 1) throw invalid(AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE);
            JsonNode choice = choices.get(0);
            if (!choice.isObject() || integer(choice, "index", AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE) != 0)
                throw invalid(AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE);
            if (!"stop".equals(text(choice, "finish_reason"))) throw invalid(AiGatewayException.ResponseIssue.FINISH_REASON);
            JsonNode message = choice.get("message");
            String content = text(message, "content");
            if (!"assistant".equals(text(message, "role")) || content == null || content.isBlank()) throw invalid(AiGatewayException.ResponseIssue.MESSAGE_CONTENT);
            JsonNode tools = message.get("tool_calls");
            if ((tools != null && !tools.isNull() && (!tools.isArray() || !tools.isEmpty()))
                    || (message.has("function_call") && !message.get("function_call").isNull())) throw invalid(AiGatewayException.ResponseIssue.MESSAGE_CONTENT);
            JsonNode usage = root.get("usage");
            int input = integer(usage, "prompt_tokens", AiGatewayException.ResponseIssue.USAGE);
            int output = integer(usage, "completion_tokens", AiGatewayException.ResponseIssue.USAGE);
            int total = integer(usage, "total_tokens", AiGatewayException.ResponseIssue.USAGE);
            if ((long) input + output != total) throw invalid(AiGatewayException.ResponseIssue.USAGE);
            // reasoning_content and cache breakdown are not stored or rendered as assistant content.
            return new AiChatResult(id, content, input, output, total, durationMs);
        } catch (IOException exception) {
            throw invalid(AiGatewayException.ResponseIssue.RESPONSE_ENVELOPE);
        }
    }

    private int integer(JsonNode object, String field, AiGatewayException.ResponseIssue issue) {
        JsonNode value = object != null && object.isObject() ? object.get(field) : null;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid(issue);
        return value.intValue();
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object != null && object.isObject() ? object.get(field) : null;
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private boolean hasTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    private long elapsedMillis(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private AiGatewayException invalid(AiGatewayException.ResponseIssue issue) {
        return new AiGatewayException(ErrorCode.AI_RESPONSE_INVALID, issue);
    }
}
