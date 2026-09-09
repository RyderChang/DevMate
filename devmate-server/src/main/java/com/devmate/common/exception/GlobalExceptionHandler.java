package com.devmate.common.exception;

import com.devmate.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int INTERNAL_ERROR_CODE = 1000;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected error while handling request method={} uri={}",
                request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(INTERNAL_ERROR_CODE, "Internal server error"));
    }
}
