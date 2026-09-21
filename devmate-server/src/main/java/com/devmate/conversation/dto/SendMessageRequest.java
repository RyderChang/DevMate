package com.devmate.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Size(max = 36) String clientRequestId,
        @NotBlank String content) {
}
