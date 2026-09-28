package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;

/** Never retains an SDK exception, request, response or locator as a cause or message. */
public final class StorageFailure extends RuntimeException {
    private final ErrorCode errorCode;
    private final boolean definitelyNoWrite;
    private final boolean retryableRead;
    public StorageFailure(ErrorCode errorCode, boolean definitelyNoWrite) {
        this(errorCode, definitelyNoWrite, true);
    }
    private StorageFailure(ErrorCode errorCode, boolean definitelyNoWrite, boolean retryableRead) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.definitelyNoWrite = definitelyNoWrite;
        this.retryableRead = retryableRead;
    }
    public ErrorCode errorCode() { return errorCode; }
    public boolean definitelyNoWrite() { return definitelyNoWrite; }
    public boolean retryableRead() { return retryableRead; }
    public static StorageFailure invalidRead() { return new StorageFailure(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE, false, false); }
}
