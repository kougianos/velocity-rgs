package com.velocity.rgs.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * Canonical persistent snapshot of a player's game session (M4 / A.9). Optimistic locking is enforced
 * via {@link #sessionVersion}; controllers translate the {@link org.springframework.dao.OptimisticLockingFailureException}
 * raised on stale writes into {@code SESSION_VERSION_CONFLICT} (A.8).
 *
 * <p>{@code active_feature_payload} stores the JSONB-serialized feature context (per A.9). The full
 * payload (including hidden state such as unrevealed Pick &amp; Collect tiles) MUST NOT leave the server;
 * see {@code activeFeatureView} projection in M5.</p>
 */
@Entity
@Table(name = "game_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(name = "game_id", nullable = false, length = 64)
    private String gameId;

    @Column(name = "math_version", nullable = false, length = 32)
    private String mathVersion;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 32)
    private GameState currentState;

    @Column(name = "current_bet", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentBet;

    @Column(name = "remaining_free_spins", nullable = false)
    private int remainingFreeSpins;

    @Column(name = "accumulated_free_spins_win", nullable = false, precision = 19, scale = 4)
    private BigDecimal accumulatedFreeSpinsWin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_feature_payload", columnDefinition = "jsonb")
    private String activeFeaturePayload;

    @Column(name = "next_action_allowed", length = 32)
    private String nextActionAllowed;

    @Version
    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
