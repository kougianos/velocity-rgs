package com.velocity.rgs.common.error;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    @ExceptionHandler(RgsException.class)
    public ResponseEntity<ApiError> handleRgs(RgsException ex) {
        return build(ex.getErrorCode(), ex.getMessage(), ex.getDetails(), ex);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return build(ErrorCode.SESSION_VERSION_CONFLICT, "Stale session version", null, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(),
                        Optional.ofNullable(fe.getDefaultMessage()).orElse("invalid")))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", details, ex);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        List<ApiError.FieldViolation> details = ex.getConstraintViolations().stream()
                .map(cv -> new ApiError.FieldViolation(cv.getPropertyPath().toString(), cv.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", details, ex);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return build(ErrorCode.VALIDATION_ERROR, Optional.ofNullable(ex.getMessage()).orElse("Bad request"), null, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex) {
        return build(ErrorCode.INTERNAL_ERROR, "Internal server error", null, ex);
    }

    private ResponseEntity<ApiError> build(ErrorCode code,
                                           String message,
                                           List<ApiError.FieldViolation> details,
                                           Throwable cause) {
        ApiError body = new ApiError(
                code.name(),
                message,
                code.httpStatus().value(),
                MDC.get("traceId"),
                Instant.now(),
                details
        );
        logAtLevel(code.logLevel(), code, message, cause);
        return ResponseEntity.status(code.httpStatus())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body);
    }

    private void logAtLevel(Level level, ErrorCode code, String message, Throwable cause) {
        switch (level) {
            case ERROR -> log.error("[{}] {}", code, message, cause);
            case WARN -> log.warn("[{}] {}", code, message);
            case INFO -> log.info("[{}] {}", code, message);
            case DEBUG -> log.debug("[{}] {}", code, message);
            case TRACE -> log.trace("[{}] {}", code, message);
        }
    }
}
