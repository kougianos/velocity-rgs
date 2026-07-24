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
    // Public replay links. Deliberately distinct from AUTH_FAILED: these are handed to strangers, so
    // "this link ran out" (410, and permanent - the link will never work again) has to be tellable from
    // "this link was edited" (400). A single 401 would render both as "log in", which is wrong twice.
    REPLAY_LINK_EXPIRED(HttpStatus.GONE, Level.INFO),
    REPLAY_LINK_INVALID(HttpStatus.BAD_REQUEST, Level.INFO),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, Level.WARN),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, Level.WARN),
    // A round whose inputs were never fully captured, so it cannot be rebuilt from its own draws. Not a
    // server fault and not retryable, which is why it is not INTERNAL_ERROR: reserving 500 for genuine
    // engine divergence is what makes a 500 from the replay path meaningful.
    ROUND_NOT_REPLAYABLE(HttpStatus.CONFLICT, Level.WARN),
    SESSION_VERSION_CONFLICT(HttpStatus.CONFLICT, Level.WARN),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, Level.WARN),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT, Level.WARN),
    INSUFFICIENT_FUNDS(HttpStatus.CONFLICT, Level.WARN),
    ORIGINAL_TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, Level.WARN),
    CURRENCY_MISMATCH(HttpStatus.CONFLICT, Level.WARN),
    BONUS_BUY_DISABLED(HttpStatus.CONFLICT, Level.WARN),
    MAX_WIN_REACHED(HttpStatus.CONFLICT, Level.WARN),
    // Responsible Gaming (§4.2). Two codes, because they are two different things and the player has to
    // be able to tell them apart: a limit that fired names itself and says when it resets, and the way
    // back to play is to wait. Self-exclusion has no way back, and rendering it as a transient error
    // with a retry would be worse than useless. Hence 409-and-recoverable versus 403-and-final.
    //
    // Logged at INFO, not WARN: a limit doing its job is the system working, not a fault. Leaving these
    // at WARN would fill the log with successful policy enforcement and devalue every other warning.
    RG_LIMIT_EXCEEDED(HttpStatus.CONFLICT, Level.INFO),
    RG_SELF_EXCLUDED(HttpStatus.FORBIDDEN, Level.INFO),
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
