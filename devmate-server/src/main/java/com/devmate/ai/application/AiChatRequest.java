package com.devmate.ai.application;

import java.util.List;
import java.util.Objects;

public record AiChatRequest(String instructions, List<AiMessage> messages, int maxOutputTokens) {
    public AiChatRequest {
        Objects.requireNonNull(instructions, "instructions must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (instructions.isBlank() || messages.isEmpty() || maxOutputTokens < 1) {
            throw new IllegalArgumentException("AI chat request must contain instructions, messages and an output limit");
        }
    }
}
