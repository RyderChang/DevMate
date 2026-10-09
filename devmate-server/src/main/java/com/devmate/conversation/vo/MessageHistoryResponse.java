package com.devmate.conversation.vo;

import java.time.Instant;

/** History-only extension; existing send response remains unchanged. */
public record MessageHistoryResponse(Long id, String role, String content, Long sequenceNo,
                                     Instant createdAt, RagEvidence evidence) {}
