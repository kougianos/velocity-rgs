package com.velocity.rgs.jackpot.persistence;

import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import com.velocity.rgs.jackpot.domain.JackpotTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface JackpotPoolRepository extends JpaRepository<JackpotPool, JackpotPoolId> {

    /** Every tier in one currency, which is the only read the lobby ever does. */
    List<JackpotPool> findByIdCurrency(String currency);

    /**
     * Adds to a pool in one statement, without reading it first.
     *
     * <p>Deliberately not a read-modify-write through the entity, even though the entity carries a
     * {@code @Version}. These four rows are the most contended in the schema - every spin of every game
     * in a currency touches them - so an optimistic-locking round trip would turn ordinary concurrency
     * into failed spins, and a player would lose a bet because somebody else happened to spin at the
     * same moment. {@code UPDATE ... SET amount = amount + ?} is resolved by the database and cannot
     * conflict.
     *
     * <p>{@code UPDATE VERSIONED} still bumps the version, so the award path (§2, exactly-once) can
     * keep using optimistic locking where a read-modify-write genuinely is required.
     *
     * @return rows updated: 1 normally, 0 if the currency runs no such pool
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VERSIONED JackpotPool p
               SET p.amount = p.amount + :delta,
                   p.updatedAt = :now
             WHERE p.id.tier = :tier
               AND p.id.currency = :currency
            """)
    int addToPool(@Param("tier") JackpotTier tier,
                  @Param("currency") String currency,
                  @Param("delta") BigDecimal delta,
                  @Param("now") Instant now);

    /**
     * Pays the pool out by resetting it to {@code newAmount}, but only if nobody has touched it since
     * it was read. Compare-and-swap on the version, which is what stops two spins racing for the same
     * tier from both winning it: the loser's update matches no row and it awards nothing.
     *
     * <p>The caller supplies the new amount rather than this resetting to the seed, because a pool does
     * not reset to exactly its seed. It holds four decimal places and a wallet pays two, so the
     * sub-cent remainder is carried forward into the next cycle - it is money players contributed, and
     * rounding it away on every award would quietly delete it.
     *
     * <p>This is the one jackpot write that genuinely needs optimistic locking, and the reason
     * {@code addToPool} bumps the version rather than bypassing it - a contribution landing between the
     * read and the CAS must invalidate the award, because the pool the winner was promised is no longer
     * the pool that exists.
     *
     * @return 1 if this caller won the pool, 0 if it lost the race
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VERSIONED JackpotPool p
               SET p.amount = :newAmount,
                   p.updatedAt = :now
             WHERE p.id.tier = :tier
               AND p.id.currency = :currency
               AND p.version = :expectedVersion
            """)
    int resetIfUnchanged(@Param("tier") JackpotTier tier,
                         @Param("currency") String currency,
                         @Param("newAmount") BigDecimal newAmount,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("now") Instant now);
}
