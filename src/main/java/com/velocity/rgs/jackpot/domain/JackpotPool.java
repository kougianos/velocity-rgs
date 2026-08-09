package com.velocity.rgs.jackpot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One progressive pool: what a tier is currently worth in one currency (§2).
 *
 * <p>The row is the pool of record. Every contribution and every award moves this row inside the same
 * transaction as the wallet movement that caused it, which is what keeps the pool and the ledger unable
 * to disagree - see {@code JackpotService}.
 *
 * <p>{@link #version} is not decoration. Two players spinning different games at the same moment both
 * contribute to the same four rows, so this is one of the few genuinely contended rows in the schema.
 */
@Entity
@Table(name = "jackpot_pool")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JackpotPool {

    @EmbeddedId
    private JackpotPoolId id;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "seed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal seedAmount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    public JackpotTier tier() {
        return id == null ? null : id.getTier();
    }

    @Transient
    public String currency() {
        return id == null ? null : id.getCurrency();
    }

    /**
     * How much this pool has grown above its floor.
     *
     * <p>Worth exposing separately from the amount because it is the honest measure of a pool's life: a
     * Mega sitting at exactly its seed has never been played for, and a strip that showed only the total
     * would present that as indistinguishable from one that has been building for a week.
     */
    @Transient
    public BigDecimal grownBy() {
        return amount.subtract(seedAmount);
    }
}
