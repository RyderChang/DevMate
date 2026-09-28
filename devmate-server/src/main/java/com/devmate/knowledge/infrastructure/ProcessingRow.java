package com.devmate.knowledge.infrastructure;

import java.time.LocalDateTime;
import com.devmate.knowledge.vo.ProcessingSummary;

public class ProcessingRow {
    public Long id;
    public long ownerUserId, projectId, documentId, generation;
    public String sourceSha256, normalizedSha256, parserVersion, strategyVersion, state, errorCode;
    public boolean active;
    public int chunkCount, retryCount, attemptCount;
    public long textBytes, usedBytes, reservedBytes, operationVersion;
    public String leaseOwner;
    public LocalDateTime leaseUntil, nextAttemptAt, createTime, updateTime;
    public ProcessingSummary summary() {
        return new ProcessingSummary(id, generation, state, sourceSha256, normalizedSha256, parserVersion,
                strategyVersion, chunkCount, textBytes, errorCode);
    }
}
