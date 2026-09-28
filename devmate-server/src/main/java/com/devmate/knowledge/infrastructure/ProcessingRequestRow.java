package com.devmate.knowledge.infrastructure;

import java.time.LocalDateTime;

public class ProcessingRequestRow {
    public Long id, processingId;
    public long ownerUserId, projectId, documentId, generation;
    public String clientRequestId, fingerprint, sourceSha256, parserVersion, strategyVersion, terminalState, errorCode;
    public boolean creator;
    public LocalDateTime expiresAt;
}
