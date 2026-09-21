package com.devmate.ai.application;

import com.devmate.common.api.ErrorCode;
import java.util.Objects;

public class AiGatewayException extends RuntimeException {
    private final ErrorCode errorCode;

    public AiGatewayException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public AiGatewayException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
