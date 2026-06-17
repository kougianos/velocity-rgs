package com.velocity.rgs.game.domain;

import com.velocity.rgs.session.domain.GameState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One row per spin (base, free, or feature-resolution iteration) per A.9 / M5 Task 5.1. The
 * {@code rng_draws} JSONB column is the canonical replay artifact for the round (see A.11).
 */
@Entity
@Table(name = "game_round")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameRound {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "state_context", nullable = false, length = 32)
    private GameState stateContext;

    @Column(name = "bet_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal betAmount;

    @Column(name = "bet_amount_minor", nullable = false)
    private long betAmountMinor;

    @Column(name = "total_win", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalWin;

    @Column(name = "total_win_minor", nullable = false)
    private long totalWinMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matrix", nullable = false, columnDefinition = "jsonb")
    private String matrix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stop_positions", nullable = false, columnDefinition = "jsonb")
    private String stopPositions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rng_draws", nullable = false, columnDefinition = "jsonb")
    private String rngDraws;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "win_lines", columnDefinition = "jsonb")
    private String winLines;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", columnDefinition = "jsonb")
    private String reasonCodes;

    @Column(name = "power_bet_active", nullable = false)
    private boolean powerBetActive;

    @Column(name = "bet_transaction_id", length = 64)
    private String betTransactionId;

    @Column(name = "win_transaction_id", length = 64)
    private String winTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
