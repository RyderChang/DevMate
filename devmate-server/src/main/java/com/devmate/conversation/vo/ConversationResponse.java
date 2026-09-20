package com.devmate.conversation.vo;

import java.time.Instant;

public record ConversationResponse(
        Long id,
        Long projectId,
        String title,
        String generationState,
        Instant createdAt,
        Instant updatedAt) {
}
