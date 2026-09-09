package com.devmate.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SUCCESS(200, "success", HttpStatus.OK),
    INVALID_PARAMETER(400, "Invalid request parameter", HttpStatus.BAD_REQUEST),
    BUSINESS_ERROR(400, "Business request failed", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(500, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
