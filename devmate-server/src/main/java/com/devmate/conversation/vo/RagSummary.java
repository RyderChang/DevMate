package com.devmate.conversation.vo;

import java.time.Instant;

public record RagSummary(String retrievalId, String spec, int queryTokens, int rounds, int inspectedPoints,
                         String templateVersion, Instant checkedAt, String offsetUnit) {}
