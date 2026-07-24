package com.velocity.rgs.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param details field-level violations, for errors that are about the request being malformed
 * @param context machine-readable facts about an error that is <em>not</em> about the request - the
 *                Responsible Gaming limit that fired, its value, when it resets. Kept separate from
 *                {@code details} because those two mean different things: a violated field tells the
 *                caller what to fix, whereas a limit tells the player why they were stopped and there
 *                is nothing to fix. Null for every error that has nothing to add, and
 *                {@code NON_NULL} keeps those responses byte-identical to before this existed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        int httpStatus,
        String traceId,
        Instant timestamp,
        List<FieldViolation> details,
        Map<String, Object> context
) {

    public record FieldViolation(String field, String reason) {}
}
