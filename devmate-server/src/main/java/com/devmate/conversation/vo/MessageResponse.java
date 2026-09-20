package com.devmate.conversation.vo;

import java.time.Instant;

public record MessageResponse(Long id, String role, String content, Long sequenceNo, Instant createdAt) {
}
