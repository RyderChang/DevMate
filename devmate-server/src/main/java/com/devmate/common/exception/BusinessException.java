package com.devmate.common.exception;

import com.devmate.common.api.ErrorCode;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final int code;
    private final String clientMessage;
    private final HttpStatus httpStatus;

    public BusinessException(String clientMessage) {
        this(ErrorCode.BUSINESS_ERROR.getCode(), clientMessage, ErrorCode.BUSINESS_ERROR.getHttpStatus());
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage(), errorCode.getHttpStatus());
    }

    public BusinessException(ErrorCode errorCode, String clientMessage) {
        this(errorCode.getCode(), clientMessage, errorCode.getHttpStatus());
    }

    public BusinessException(ErrorCode errorCode, String clientMessage, Throwable cause) {
        this(errorCode.getCode(), clientMessage, errorCode.getHttpStatus(), cause);
    }

    public BusinessException(int code, String clientMessage, HttpStatus httpStatus) {
        this(code, clientMessage, httpStatus, null);
    }

    public BusinessException(int code, String clientMessage, HttpStatus httpStatus, Throwable cause) {
        super(Objects.requireNonNull(clientMessage, "clientMessage must not be null"), cause);
        this.code = code;
        this.clientMessage = clientMessage;
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus must not be null");
    }

    public int getCode() {
        return code;
    }

    public String getClientMessage() {
        return clientMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
