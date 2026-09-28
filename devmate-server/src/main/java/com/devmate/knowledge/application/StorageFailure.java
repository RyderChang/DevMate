package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;

/** Never retains an SDK exception, request, response or locator as a cause or message. */
public final class StorageFailure extends RuntimeException {
    private final ErrorCode errorCode;
    private final boolean definitelyNoWrite;
    public StorageFailure(ErrorCode errorCode, boolean definitelyNoWrite) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.definitelyNoWrite = definitelyNoWrite;
    }
    public ErrorCode errorCode() { return errorCode; }
    public boolean definitelyNoWrite() { return definitelyNoWrite; }
}
