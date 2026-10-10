package com.devmate.ai.application;

import com.devmate.common.api.ErrorCode;
import java.util.Objects;

public class AiGatewayException extends RuntimeException {
    public enum ResponseIssue { UPSTREAM_STATUS, RESPONSE_SIZE, RESPONSE_ENCODING, RESPONSE_ENVELOPE,
        FINISH_REASON, MESSAGE_CONTENT, USAGE, UNCLASSIFIED }
    private final ErrorCode errorCode;
    private final ResponseIssue responseIssue;

    public AiGatewayException(ErrorCode errorCode) {
        this(errorCode, (Throwable) null);
    }

    public AiGatewayException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage(), cause);
        this.errorCode = errorCode;
        this.responseIssue = null;
    }

    public AiGatewayException(ErrorCode errorCode, ResponseIssue responseIssue) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
        this.responseIssue = Objects.requireNonNull(responseIssue);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public ResponseIssue getResponseIssue() { return responseIssue; }
}
