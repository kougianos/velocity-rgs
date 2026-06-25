package com.velocity.rgs.blackjack.domain;

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
 * One row per blackjack round. Unlike the one-shot slot/roulette rounds, a blackjack round is multi-step: it
 * is written as {@code IN_PROGRESS} at the deal and updated in place through each action until it settles, so
 * the active round for a session is the row with {@code status = IN_PROGRESS}. The {@code shoe} JSONB column
 * (shuffled order + draw cursor) makes the whole round deterministic and auditable across HTTP calls;
 * {@code rng_draws} captures the Fisher–Yates shuffle that produced that order. Kept as a separate table from
 * the slot {@code game_round} / roulette {@code roulette_round} so those paths are untouched - only the demo
 * history-list endpoint merges all three.
 */
@Entity
@Table(name = "blackjack_round")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlackjackRound {

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

    @Column(name = "bet", nullable = false, precision = 19, scale = 4)
    private BigDecimal bet;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RoundStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shoe", nullable = false, columnDefinition = "jsonb")
    private String shoe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "player_hands", nullable = false, columnDefinition = "jsonb")
    private String playerHands;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dealer_hand", nullable = false, columnDefinition = "jsonb")
    private String dealerHand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcomes", nullable = false, columnDefinition = "jsonb")
    private String outcomes;

    @Column(name = "total_bet", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalBet;

    @Column(name = "total_bet_minor", nullable = false)
    private long totalBetMinor;

    @Column(name = "total_win", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalWin;

    @Column(name = "total_win_minor", nullable = false)
    private long totalWinMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rng_draws", nullable = false, columnDefinition = "jsonb")
    private String rngDraws;

    @Column(name = "bet_transaction_id", length = 64)
    private String betTransactionId;

    @Column(name = "win_transaction_id", length = 64)
    private String winTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
