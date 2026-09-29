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
    DOCUMENT_NOT_FOUND(404, "Document not found", HttpStatus.NOT_FOUND),
    DOCUMENT_PROCESSING_DISABLED(503, "Document processing is disabled", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_INDEX_DISABLED(503, "Document indexing is disabled", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_RETRIEVAL_DISABLED(503, "Document retrieval is disabled", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_RETRIEVAL_UNAVAILABLE(503, "Document retrieval could not be confirmed", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_RETRIEVAL_LIMIT(409, "Document retrieval capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_NOT_CHUNKED(409, "Document has no complete active chunks", HttpStatus.CONFLICT),
    DOCUMENT_INDEX_CONFLICT(409, "Index request fingerprint conflicts", HttpStatus.CONFLICT),
    DOCUMENT_INDEX_IN_PROGRESS(409, "Document indexing is in progress", HttpStatus.CONFLICT),
    DOCUMENT_INDEX_REQUEST_LIMIT(409, "Index request capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_INDEX_CAPACITY_EXCEEDED(409, "Index or cleanup capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_INDEX_TOKEN_LIMIT(409, "Local indexing token capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_NOT_STORED(409, "Document storage is not complete", HttpStatus.CONFLICT),
    DOCUMENT_PROCESSING_CONFLICT(409, "Processing request fingerprint conflicts", HttpStatus.CONFLICT),
    DOCUMENT_PROCESSING_TERMINATED(409, "Processing request has terminated", HttpStatus.CONFLICT),
    DOCUMENT_PROCESSING_IN_PROGRESS(409, "Document processing or cleanup is in progress", HttpStatus.CONFLICT),
    DOCUMENT_PROCESSING_REQUEST_LIMIT(409, "Processing request capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_CHUNK_CAPACITY_EXCEEDED(409, "Project chunk capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_REQUEST_CONFLICT(409, "Document request fingerprint conflicts", HttpStatus.CONFLICT),
    DOCUMENT_REQUEST_IN_PROGRESS(409, "Document storage is not yet confirmed", HttpStatus.CONFLICT),
    DOCUMENT_REQUEST_TERMINATED(409, "Document request has terminated", HttpStatus.CONFLICT),
    DOCUMENT_CAPACITY_EXCEEDED(409, "Project document capacity exceeded", HttpStatus.CONFLICT),
    DOCUMENT_TOO_LARGE(413, "Document or request exceeds the size limit", HttpStatus.PAYLOAD_TOO_LARGE),
    DOCUMENT_FORMAT_UNSUPPORTED(415, "Only UTF-8 txt and md documents are supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    MULTIPART_UNSUPPORTED(415, "Multipart requests are not supported for this endpoint", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_UPLOAD_LIMITED(429, "Document upload concurrency limit reached", HttpStatus.TOO_MANY_REQUESTS),
    KNOWLEDGE_SERVICE_DISABLED(503, "Document storage is disabled", HttpStatus.SERVICE_UNAVAILABLE),
    KNOWLEDGE_STORAGE_UNAVAILABLE(503, "Document storage is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    KNOWLEDGE_STORAGE_TIMEOUT(504, "Document storage operation timed out", HttpStatus.GATEWAY_TIMEOUT),
    KNOWLEDGE_DATABASE_UNAVAILABLE(503, "Document state cannot be confirmed", HttpStatus.SERVICE_UNAVAILABLE),
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
