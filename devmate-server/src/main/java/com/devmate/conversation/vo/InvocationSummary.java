package com.devmate.conversation.vo;

import java.time.Instant;

public record InvocationSummary(
        String status,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Long durationMs,
        Instant completedAt) {
}
