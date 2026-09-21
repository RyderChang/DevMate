package com.devmate.ai.application;

import java.util.Objects;

public record AiMessage(Role role, String content) {
    public AiMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public enum Role {
        USER("user"),
        ASSISTANT("assistant");

        private final String apiValue;

        Role(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }
}
