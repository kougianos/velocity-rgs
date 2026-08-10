package com.velocity.rgs.jackpot.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one spin fed the pools, and where they stand afterwards (§2).
 *
 * <p>The pools travel back on the spin response rather than being fetched separately, because the claim
 * being made is causal: <em>this</em> spin moved <em>these</em> numbers. A client that had to re-poll
 * for them could only show that the pools were different a moment later, which is a much weaker thing
 * to have watched.
 *
 * @param amount what actually reached the pools, summed from the per-tier deltas rather than recomputed
 *               from the rate - the figure shown to a player is the figure the pools received
 * @param pools  every tier after this contribution, in ladder order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JackpotContribution(
        BigDecimal amount,
        String currency,
        List<JackpotPoolView> pools
) {

    /** A spin that fed nothing: jackpots off, or a game that does not contribute. */
    public static JackpotContribution none(String currency) {
        return new JackpotContribution(BigDecimal.ZERO, currency, List.of());
    }

    /**
     * Whether this is worth putting on screen at all.
     *
     * <p>{@code @JsonIgnore} is load-bearing. Jackson reads {@code isPresent()} as a property and would
     * write a {@code "present"} field that no record component can accept on the way back in - and this
     * payload does come back in, because an idempotent replay deserialises the stored response. With
     * {@code fail-on-unknown-properties} enabled that turns a retried spin into a 500: the money is
     * fine, the round is fine, and the client is told the server broke.
     */
    @JsonIgnore
    public boolean isPresent() {
        return amount != null && amount.signum() > 0;
    }
}
