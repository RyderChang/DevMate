package com.devmate.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SUCCESS(200, "success", HttpStatus.OK),
    INVALID_PARAMETER(400, "Invalid request parameter", HttpStatus.BAD_REQUEST),
    BUSINESS_ERROR(400, "Business request failed", HttpStatus.BAD_REQUEST),
    USERNAME_ALREADY_EXISTS(409, "Username already exists", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(401, "Invalid username or password", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(401, "Authentication required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "Access denied", HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(404, "User not found", HttpStatus.NOT_FOUND),
    PROJECT_NOT_FOUND(404, "Project not found", HttpStatus.NOT_FOUND),
    CONVERSATION_NOT_FOUND(404, "Conversation not found", HttpStatus.NOT_FOUND),
    AI_SERVICE_DISABLED(503, "AI service is disabled", HttpStatus.SERVICE_UNAVAILABLE),
    AI_REQUEST_IN_PROGRESS(409, "An AI response is already being generated", HttpStatus.CONFLICT),
    AI_REQUEST_EXPIRED(503, "The previous AI request expired", HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_RATE_LIMITED(503, "AI provider rate limit reached", HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_TIMEOUT(504, "AI provider request timed out", HttpStatus.GATEWAY_TIMEOUT),
    AI_PROVIDER_UNAVAILABLE(503, "AI provider is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    AI_RESPONSE_INVALID(502, "AI provider returned an invalid response", HttpStatus.BAD_GATEWAY),
    DEFAULT_ROLE_NOT_CONFIGURED(500, "Default user role is not configured", HttpStatus.INTERNAL_SERVER_ERROR),
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
