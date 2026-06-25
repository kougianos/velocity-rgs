package com.velocity.rgs.roulette.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per roulette spin. The {@code rng_draws} JSONB column is the canonical replay artifact (the single
 * wheel draw); {@code bets} and {@code winning_bets} capture the full betting layout and its settlement for
 * audit. Kept as a separate table from the slot {@code game_round} so the slot replay/reconciliation path is
 * untouched - only the demo history-list endpoint merges the two.
 */
@Entity
@Table(name = "roulette_round")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouletteRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(name = "round_id", nullable = false, unique = true, length = 64)
    private String roundId;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "math_version", nullable = false, length = 32)
    private String mathVersion;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "winning_number", nullable = false)
    private int winningNumber;

    @Column(name = "winning_color", nullable = false, length = 8)
    private String winningColor;

    @Column(name = "total_bet", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalBet;

    @Column(name = "total_bet_minor", nullable = false)
    private long totalBetMinor;

    @Column(name = "total_win", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalWin;

    @Column(name = "total_win_minor", nullable = false)
    private long totalWinMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bets", nullable = false, columnDefinition = "jsonb")
    private String bets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winning_bets", nullable = false, columnDefinition = "jsonb")
    private String winningBets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rng_draws", nullable = false, columnDefinition = "jsonb")
    private String rngDraws;

    @Column(name = "bet_transaction_id", length = 64)
    private String betTransactionId;

    @Column(name = "win_transaction_id", length = 64)
    private String winTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
