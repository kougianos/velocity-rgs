package com.velocity.rgs.common.idempotency;

/**
 * Cached idempotent response payload.
 */
public record IdempotencyResult(int statusCode, String responseBody, String payloadHash) {
}
