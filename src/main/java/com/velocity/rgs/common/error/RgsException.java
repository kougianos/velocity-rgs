package com.velocity.rgs.common.error;

import lombok.Getter;

/**
 * Base exception type carrying a normative ErrorCode and optional field violation details.
 */
@Getter
public class RgsException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient java.util.List<ApiError.FieldViolation> details;

    public RgsException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public RgsException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public RgsException(ErrorCode errorCode, String message,
                        java.util.List<ApiError.FieldViolation> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }
}
