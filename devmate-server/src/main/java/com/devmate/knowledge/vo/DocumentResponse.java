package com.devmate.knowledge.vo;

import java.time.Instant;

public record DocumentResponse(Long id, Long projectId, String filename, String fileType,
        long byteSize, String sourceType, String storageState, String failureCode,
        Instant createTime, Instant updateTime) {}
