package com.devmate.common.exception;

import com.devmate.common.api.Result;
import com.devmate.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        LOGGER.warn("Business request failed method={} uri={} code={}",
                request.getMethod(), request.getRequestURI(), exception.getCode());
        return ResponseEntity.status(exception.getHttpStatus())
                .body(Result.error(exception.getCode(), exception.getClientMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        String details = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(error -> String.valueOf(error.getDefaultMessage())))
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return invalidParameter(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        String details = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        return invalidParameter(details);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException exception) {
        String details = exception.getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return invalidParameter(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableMessage() {
        return invalidParameter("");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        LOGGER.warn("Access denied method={} uri={}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getHttpStatus())
                .body(Result.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected error while handling request method={} uri={}",
                request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<Result<Void>> invalidParameter(String details) {
        String message = details.isBlank()
                ? ErrorCode.INVALID_PARAMETER.getMessage()
                : ErrorCode.INVALID_PARAMETER.getMessage() + ": " + details;
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER.getHttpStatus())
                .body(Result.error(ErrorCode.INVALID_PARAMETER.getCode(), message));
    }
}
