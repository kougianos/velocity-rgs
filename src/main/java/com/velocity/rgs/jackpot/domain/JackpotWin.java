package com.velocity.rgs.jackpot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One awarded jackpot (§2).
 *
 * <p>Both the audit record and the last line of the exactly-once defence: {@code round_id} and
 * {@code tx_id} are unique, so a second award for the same round fails the insert and takes the whole
 * transaction down with it rather than paying twice. See {@code V15__jackpot_win.sql} for why the
 * guarantee is enforced in three places instead of one.
 *
 * <p>{@link #amount} and {@link #seedAmount} are both recorded because the pool row is reset in the
 * same transaction. After that reset nothing else in the schema remembers what the prize was worth, so
 * a row that stored only "MEGA was won" would leave the size of the payout unreconstructable.
 */
@Entity
@Table(name = "jackpot_win")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JackpotWin {

    @Id
    @Column(name = "win_id", nullable = false, length = 64)
    private String winId;

    @Column(name = "round_id", nullable = false, length = 64)
    private String roundId;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private JackpotTier tier;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "seed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal seedAmount;

    @Column(name = "tx_id", nullable = false, length = 96)
    private String txId;

    @Column(name = "won_at", nullable = false)
    private Instant wonAt;
}
