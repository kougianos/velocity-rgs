package com.velocity.rgs.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller endpoint as idempotent. The aspect reads the
 * {@code Idempotency-Key} HTTP header, computes a payload hash and replays
 * stored responses on duplicate calls.
 *
 * @see IdempotencyAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * Stable scope string per Idempotency Policy table (A.6).
     * Supports SpEL-like placeholders {@code {playerId}} and {@code {transactionId}}
     * resolved against method arguments via the SpEL evaluation context.
     */
    String scope();

    /**
     * Replay window in hours. Defaults to 24 for slot actions; wallet endpoints use 48.
     */
    long ttlHours() default 24;
}
