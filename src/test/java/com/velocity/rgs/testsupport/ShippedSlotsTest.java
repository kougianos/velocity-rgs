package com.velocity.rgs.testsupport;

import com.velocity.rgs.slot.math.domain.BonusBuyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard over the guards. {@link ShippedSlots} is what the three RTP verifications enumerate their
 * games from, and it runs only under {@code -Prtp} - so if it silently returned nothing, the entire RTP
 * suite would go green without simulating a spin and nobody would find out until a game shipped
 * mispriced. This runs in the default build, in milliseconds, and fails loudly instead.
 *
 * <p>Cheap insurance against the exact failure that motivated it: two games with a purchasable feature
 * that no guard covered, one paying 540% of its declared RTP and the other 76%, for as long as they had
 * existed.
 */
class ShippedSlotsTest {

    @Test
    void readsEveryGameInTheShippedCatalog() {
        assertThat(ShippedSlots.all())
                .containsExactly("aztec-fire", "frost-crown", "inferno-riches", "jade-tiger",
                        "gilded-cascade", "dragon-hoard");
    }

    /** A catalog entry that will not load is a game the RTP guards would skip rather than fail on. */
    @Test
    void everyCatalogEntryResolvesToLoadableMath() {
        for (String gameId : ShippedSlots.all()) {
            assertThat(ShippedSlots.math(gameId).gameId())
                    .as("catalog entry %s does not load", gameId)
                    .isEqualTo(gameId);
        }
    }

    /**
     * The regression this whole mechanism exists for. Both wild games offer a free-spins buy and were
     * missing from the hand-written guard list; derived from the math, they cannot be.
     */
    @Test
    void discoversEveryGameOfferingAFreeSpinsBuy() {
        List<String> games = ShippedSlots.offering(BonusBuyType.FREE_SPINS_BUY);

        assertThat(games).contains("gilded-cascade", "dragon-hoard");
        assertThat(games).hasSameElementsAs(ShippedSlots.all());
    }

    @Test
    void discoversEveryGameOfferingAHoldSpinBuy() {
        assertThat(ShippedSlots.offering(BonusBuyType.HOLD_SPIN_BUY)).containsExactly("dragon-hoard");
    }

    /** An empty list would make a {@code @MethodSource} guard pass vacuously, which is the one thing it must not do. */
    @Test
    void neverReportsAnEmptyCatalog() {
        assertThat(ShippedSlots.all()).isNotEmpty();
        assertThat(ShippedSlots.offering(BonusBuyType.FREE_SPINS_BUY)).isNotEmpty();
        assertThat(ShippedSlots.offering(BonusBuyType.HOLD_SPIN_BUY)).isNotEmpty();
    }
}
