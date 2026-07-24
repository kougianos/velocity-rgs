package com.velocity.rgs.common.error;

import lombok.Getter;

/**
 * Base exception type carrying a normative ErrorCode and optional field violation details.
 */
@Getter
public class RgsException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient java.util.List<ApiError.FieldViolation> details;

    /**
     * Machine-readable facts the client needs to render this error properly, or null when the message
     * says everything. Populated by Responsible Gaming so a limit can name itself; see
     * {@link com.velocity.rgs.rg.RgPolicyService}.
     */
    private final transient java.util.Map<String, Object> context;

    public RgsException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public RgsException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public RgsException(ErrorCode errorCode, String message,
                        java.util.List<ApiError.FieldViolation> details, Throwable cause) {
        this(errorCode, message, details, null, cause);
    }

    public RgsException(ErrorCode errorCode, String message,
                        java.util.List<ApiError.FieldViolation> details,
                        java.util.Map<String, Object> context, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
        this.context = context;
    }
}
