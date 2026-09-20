package com.devmate.ai.application;

import java.util.Objects;

public record AiChatResult(
        String providerRequestId,
        String content,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        long durationMs) {
    public AiChatResult {
        Objects.requireNonNull(providerRequestId, "providerRequestId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (providerRequestId.isBlank() || content.isBlank() || durationMs < 0) {
            throw new IllegalArgumentException("AI chat result contains invalid values");
        }
    }
}
