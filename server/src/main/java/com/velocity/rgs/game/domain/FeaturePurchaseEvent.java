package com.velocity.rgs.game.domain;

import com.velocity.rgs.math.domain.BonusBuyType;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit row for every Bonus Buy purchase (Section 4 Implementation Notes / A.9).
 */
@Entity
@Table(name = "feature_purchase_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeaturePurchaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "player_id", nullable = false, length = 64)
    private String playerId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "buy_type", nullable = false, length = 32)
    private BonusBuyType buyType;

    @Column(name = "cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal cost;

    @Column(name = "cost_minor", nullable = false)
    private long costMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_state", nullable = false, length = 32)
    private GameState resultingState;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "bet_size", nullable = false, precision = 19, scale = 4)
    private BigDecimal betSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
