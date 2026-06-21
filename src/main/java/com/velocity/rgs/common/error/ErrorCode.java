package com.velocity.rgs.common.error;

import org.springframework.http.HttpStatus;
import org.slf4j.event.Level;

/**
 * Canonical error codes per Appendix A.8. HTTP status mapping is normative.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, Level.WARN),
    AUTH_FAILED(HttpStatus.UNAUTHORIZED, Level.INFO),
    FORBIDDEN_ACTION(HttpStatus.FORBIDDEN, Level.INFO),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, Level.WARN),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, Level.WARN),
    SESSION_VERSION_CONFLICT(HttpStatus.CONFLICT, Level.WARN),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, Level.WARN),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT, Level.WARN),
    INSUFFICIENT_FUNDS(HttpStatus.CONFLICT, Level.WARN),
    ORIGINAL_TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, Level.WARN),
    CURRENCY_MISMATCH(HttpStatus.CONFLICT, Level.WARN),
    BONUS_BUY_DISABLED(HttpStatus.CONFLICT, Level.WARN),
    MAX_WIN_REACHED(HttpStatus.CONFLICT, Level.WARN),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, Level.ERROR);

    private final HttpStatus httpStatus;
    private final Level logLevel;

    ErrorCode(HttpStatus httpStatus, Level logLevel) {
        this.httpStatus = httpStatus;
        this.logLevel = logLevel;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public Level logLevel() {
        return logLevel;
    }
}
