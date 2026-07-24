package com.velocity.rgs.rg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One player's Responsible Gaming limits and blocks (§4.2).
 *
 * <p>Keyed by player rather than session, which is the substance of the feature and not a schema
 * detail: a limit that a new session resets is not a limit. Every field is nullable and null means
 * "not set" - a player who has never opened the RG panel has no row, and that reads the same as a row
 * with nothing set.
 *
 * <p>Consumption is absent by design. Wagered, won and net loss are derived from {@code
 * wallet_transaction} at check time rather than counted here, so the limit is enforced against the same
 * ledger an auditor would read instead of against a counter that can drift from it.
 */
@Entity
@Table(name = "rg_limit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RgLimit {

    @Id
    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    /** Minutes of continuous play allowed before play is blocked. Null = unset. */
    @Column(name = "session_limit_minutes")
    private Integer sessionLimitMinutes;

    /** Net loss (staked minus won) allowed within the period. Null = unset. */
    @Column(name = "loss_limit", precision = 19, scale = 4)
    private BigDecimal lossLimit;

    /** Total stake allowed within the period, win or lose. Null = unset. */
    @Column(name = "wager_limit", precision = 19, scale = 4)
    private BigDecimal wagerLimit;

    /** How often to interrupt play with a reality check. Null = unset. */
    @Column(name = "reality_check_minutes")
    private Integer realityCheckMinutes;

    /** Start of the window the loss and wager limits are measured over. */
    @Column(name = "period_started_at", nullable = false)
    private Instant periodStartedAt;

    /** Start of the current play session, which the session-duration limit is measured against. */
    @Column(name = "session_started_at")
    private Instant sessionStartedAt;

    /** Last staked action, used to decide whether a new play session has begun. */
    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "last_reality_check_at")
    private Instant lastRealityCheckAt;

    /** When the current cool-off ends. Null, or in the past, means the player is not cooling off. */
    @Column(name = "cool_off_until")
    private Instant coolOffUntil;

    /**
     * When the player self-excluded. There is deliberately no paired expiry: self-exclusion is not a
     * timed break, and a column for an end date would invite one to be set.
     */
    @Column(name = "self_excluded_at")
    private Instant selfExcludedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A player with no limits set and no blocks - what every player starts as. */
    public static RgLimit fresh(String playerId, Instant now) {
        return RgLimit.builder()
                .playerId(playerId)
                .periodStartedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public boolean isSelfExcluded() {
        return selfExcludedAt != null;
    }

    public boolean isCoolingOff(Instant now) {
        return coolOffUntil != null && coolOffUntil.isAfter(now);
    }
}
