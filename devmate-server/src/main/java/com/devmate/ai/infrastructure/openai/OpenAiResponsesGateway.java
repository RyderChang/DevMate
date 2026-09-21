package com.devmate.ai.infrastructure.openai;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.config.AiProperties;
import com.devmate.common.api.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

public final class OpenAiResponsesGateway implements AiGateway {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public OpenAiResponsesGateway(RestClient restClient, ObjectMapper objectMapper, AiProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override public boolean enabled() { return true; }
    @Override public String provider() { return "openai"; }
    @Override public String model() { return properties.getOpenai().getModel(); }

    @Override
    public AiChatResult chat(AiChatRequest request) {
        long started = System.nanoTime();
        try {
            return restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getOpenai().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request))
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            throw new AiGatewayException(ErrorCode.AI_PROVIDER_RATE_LIMITED);
                        }
                        if (status < 200 || status >= 300) {
                            ErrorCode error = status >= 500 || status == 401 || status == 403
                                    ? ErrorCode.AI_PROVIDER_UNAVAILABLE : ErrorCode.AI_RESPONSE_INVALID;
                            throw new AiGatewayException(error);
                        }
                        byte[] body = readLimited(response.getBody());
                        return parse(body, elapsedMillis(started));
                    });
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasCause(exception, SocketTimeoutException.class)
                    || hasCause(exception, HttpTimeoutException.class)) {
                throw new AiGatewayException(ErrorCode.AI_PROVIDER_TIMEOUT, exception);
            }
            throw new AiGatewayException(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception);
        } catch (RuntimeException exception) {
            throw new AiGatewayException(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private Map<String, Object> requestBody(AiChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("store", false);
        body.put("stream", false);
        body.put("max_output_tokens", request.maxOutputTokens());
        body.put("instructions", request.instructions());
        List<Map<String, String>> input = new ArrayList<>();
        request.messages().forEach(message -> input.add(Map.of(
                "role", message.role().apiValue(), "content", message.content())));
        body.put("input", input);
        return body;
    }

    private byte[] readLimited(InputStream input) {
        try (input) {
            byte[] body = input.readNBytes(properties.getMaxResponseBytes() + 1);
            if (body.length > properties.getMaxResponseBytes()) {
                throw new AiGatewayException(ErrorCode.AI_RESPONSE_INVALID);
            }
            return body;
        } catch (IOException exception) {
            throw new AiGatewayException(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private AiChatResult parse(byte[] body, long durationMs) {
        try {
            JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            if (!root.isObject() || !"completed".equals(text(root, "status"))) {
                throw invalid();
            }
            String responseId = text(root, "id");
            JsonNode output = root.get("output");
            if (responseId == null || responseId.isBlank() || responseId.length() > 255
                    || output == null || !output.isArray()) {
                throw invalid();
            }
            StringBuilder visibleText = new StringBuilder();
            for (JsonNode item : output) {
                if (!item.isObject()) {
                    throw invalid();
                }
                String itemType = text(item, "type");
                if (isExecutionOutput(itemType)) {
                    throw invalid();
                }
                if (!"message".equals(itemType)) {
                    continue;
                }
                if (!"assistant".equals(text(item, "role"))) {
                    throw invalid();
                }
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) {
                    throw invalid();
                }
                for (JsonNode part : content) {
                    if (!part.isObject()) {
                        throw invalid();
                    }
                    String type = text(part, "type");
                    if ("output_text".equals(type)) {
                        appendRequired(visibleText, text(part, "text"));
                    } else if ("refusal".equals(type)) {
                        appendRequired(visibleText, text(part, "refusal"));
                    }
                }
            }
            if (visibleText.toString().isBlank()) {
                throw invalid();
            }
            JsonNode usage = root.get("usage");
            if (usage != null && !usage.isNull() && !usage.isObject()) {
                throw invalid();
            }
            Integer inputTokens = nullableNonNegativeInteger(usage, "input_tokens");
            Integer outputTokens = nullableNonNegativeInteger(usage, "output_tokens");
            Integer totalTokens = nullableNonNegativeInteger(usage, "total_tokens");
            return new AiChatResult(responseId, visibleText.toString(), inputTokens,
                    outputTokens, totalTokens, durationMs);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException(ErrorCode.AI_RESPONSE_INVALID, exception);
        }
    }

    private Integer nullableNonNegativeInteger(JsonNode object, String field) {
        if (object == null || object.isNull() || object.get(field) == null || object.get(field).isNull()) {
            return null;
        }
        JsonNode value = object.get(field);
        if (!object.isObject() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid();
        }
        return value.intValue();
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private void appendRequired(StringBuilder target, String value) {
        if (value == null) {
            throw invalid();
        }
        target.append(value);
    }

    private boolean isExecutionOutput(String type) {
        return type != null && (type.endsWith("_call") || "mcp_approval_request".equals(type));
    }

    private AiGatewayException invalid() {
        return new AiGatewayException(ErrorCode.AI_RESPONSE_INVALID);
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
