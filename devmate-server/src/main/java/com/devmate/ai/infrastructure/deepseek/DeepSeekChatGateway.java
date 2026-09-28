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
        if (request.maxOutputTokens() > properties.getMaxOutputTokens()) throw invalid();
        long started = System.nanoTime();
        try {
            return client.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDeepseek().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(requestBody(request))
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) throw new AiGatewayException(ErrorCode.AI_PROVIDER_RATE_LIMITED);
                        if (status < 200 || status >= 300) {
                            throw new AiGatewayException(status >= 500 || status == 401 || status == 402 || status == 403
                                    ? ErrorCode.AI_PROVIDER_UNAVAILABLE : ErrorCode.AI_RESPONSE_INVALID);
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
            byte[] bytes = input.readNBytes(properties.getMaxResponseBytes() + 1);
            if (bytes.length > properties.getMaxResponseBytes()) throw invalid();
            return bytes;
        } catch (IOException exception) {
            throw new AiGatewayException(hasTimeout(exception)
                    ? ErrorCode.AI_PROVIDER_TIMEOUT : ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private AiChatResult parse(byte[] bytes, long durationMs) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || !"chat.completion".equals(text(root, "object"))) throw invalid();
            String id = text(root, "id");
            String responseModel = text(root, "model");
            JsonNode choices = root.get("choices");
            if (id == null || id.isBlank() || id.length() > 255 || responseModel == null || responseModel.isBlank()
                    || responseModel.length() > 100 || choices == null || !choices.isArray() || choices.size() != 1) throw invalid();
            JsonNode choice = choices.get(0);
            if (!choice.isObject() || !"stop".equals(text(choice, "finish_reason"))
                    || integer(choice, "index") != 0) throw invalid();
            JsonNode message = choice.get("message");
            String content = text(message, "content");
            if (!"assistant".equals(text(message, "role")) || content == null || content.isBlank()) throw invalid();
            JsonNode tools = message.get("tool_calls");
            if ((tools != null && !tools.isNull() && (!tools.isArray() || !tools.isEmpty()))
                    || (message.has("function_call") && !message.get("function_call").isNull())) throw invalid();
            JsonNode usage = root.get("usage");
            int input = integer(usage, "prompt_tokens");
            int output = integer(usage, "completion_tokens");
            int total = integer(usage, "total_tokens");
            if ((long) input + output != total) throw invalid();
            // reasoning_content and cache breakdown are not stored or rendered as assistant content.
            return new AiChatResult(id, content, input, output, total, durationMs);
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private int integer(JsonNode object, String field) {
        JsonNode value = object != null && object.isObject() ? object.get(field) : null;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
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
    private AiGatewayException invalid() { return new AiGatewayException(ErrorCode.AI_RESPONSE_INVALID); }
}
