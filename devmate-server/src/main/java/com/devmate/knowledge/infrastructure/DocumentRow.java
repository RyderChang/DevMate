package com.devmate.knowledge.infrastructure;

import com.devmate.knowledge.application.ObjectLocation;
import com.devmate.knowledge.application.StorageState;
import com.devmate.knowledge.application.RemotePhase;
import com.devmate.knowledge.vo.DocumentResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Persistence data stays inside knowledge and is never used as an API response. */
public class DocumentRow {
    public Long id;
    public Long ownerUserId;
    public Long projectId;
    public String filename;
    public String fileType;
    public long byteSize;
    public String sha256;
    public String sourceType;
    public String bucket;
    public String objectKey;
    public String putToken;
    public StorageState storageState;
    public RemotePhase remotePhase;
    public String errorCode;
    public long operationVersion;
    public String leaseOwner;
    public LocalDateTime leaseUntil;
    public int retryCount;
    public LocalDateTime nextAttemptAt;
    public boolean needsManual;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;

    public ObjectLocation location() { return new ObjectLocation(bucket, objectKey); }
    public DocumentResponse response() {
        return new DocumentResponse(id, projectId, filename, fileType, byteSize, sourceType,
                storageState.name(), errorCode, createTime.toInstant(ZoneOffset.UTC), updateTime.toInstant(ZoneOffset.UTC));
    }
}
