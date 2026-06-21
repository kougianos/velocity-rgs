package com.velocity.rgs.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        int httpStatus,
        String traceId,
        Instant timestamp,
        List<FieldViolation> details
) {

    public record FieldViolation(String field, String reason) {}
}
