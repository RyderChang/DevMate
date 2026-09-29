package com.devmate.conversation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagMessageRequest(@NotBlank @Size(max=36) String clientRequestId,
                                @NotBlank String content) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported RAG request field");
    }
}
