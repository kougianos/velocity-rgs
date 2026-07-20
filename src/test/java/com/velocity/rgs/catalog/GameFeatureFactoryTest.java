package com.velocity.rgs.catalog;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.WinModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lobby advertises mechanics derived from the math config, so the property worth testing is not
 * the wording - it is that the advertisement cannot drift from the engine.
 *
 * <p>The parameterised tests below therefore assert a biconditional over every shipped slot: a feature
 * card exists <em>if and only if</em> the block that switches the mechanic on is enabled. That is what
 * stops the lobby promising a Hold &amp; Spin the reels will never run, and equally stops a newly
 * enabled mechanic shipping unadvertised.
 */
class GameFeatureFactoryTest {

    /** Every slot registered in application.yml. */
    private static final String[] SLOTS = {
            "aztec-fire", "frost-crown", "inferno-riches", "jade-tiger", "gilded-cascade", "dragon-hoard"
    };

    private static SlotMathDefinition math(String gameId) {
        return new SlotMathLoader().load(gameId, "v1").math();
    }

    private static Map<String, GameFeature> byKey(SlotMathDefinition math) {
        return GameFeatureFactory.forSlot(math).stream()
                .collect(Collectors.toMap(GameFeature::key, Function.identity()));
    }

    // ---------------------------------------------------------------- the drift guard

    @ParameterizedTest
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger",
            "gilded-cascade", "dragon-hoard"})
    void aMechanicIsAdvertisedExactlyWhenItIsConfigured(String gameId) {
        SlotMathDefinition math = math(gameId);
        Map<String, GameFeature> features = byKey(math);

        assertThat(features.containsKey("CASCADES"))
                .as("%s advertises cascades iff math.cascades is enabled", gameId)
                .isEqualTo(math.cascades().enabled());
        assertThat(features.containsKey("HOLD_SPIN"))
                .as("%s advertises Hold & Spin iff math.respins is enabled", gameId)
                .isEqualTo(math.respins().enabled());
        assertThat(features.containsKey("JACKPOTS"))
                .as("%s advertises jackpots iff respins declare tiers", gameId)
                .isEqualTo(math.respins().enabled() && !math.respins().jackpots().isEmpty());
        assertThat(features.containsKey("WILDS"))
                .as("%s advertises wild behaviours iff math.wildFeatures has one active", gameId)
                .isEqualTo(math.wildFeatures().active());
        assertThat(features.containsKey("BONUS_BUY"))
                .as("%s advertises bonus buy iff it configures a buy option", gameId)
                .isEqualTo(!math.bonusBuyOptions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger",
            "gilded-cascade", "dragon-hoard"})
    void everyCardIsRenderableAndFullyInterpolated(String gameId) {
        List<GameFeature> features = GameFeatureFactory.forSlot(math(gameId));
        assertThat(features).isNotEmpty();
        for (GameFeature f : features) {
            assertThat(f.key()).isNotBlank();
            assertThat(f.name()).isNotBlank();
            assertThat(f.icon()).isNotBlank();
            assertThat(f.summary()).isNotBlank();
            assertThat(f.facts()).isNotEmpty()
                    .as("%s/%s facts", gameId, f.key())
                    // A fact reading "null coins" means a config accessor came back empty and the
                    // string was built anyway - it renders straight into the lobby, so fail here.
                    .allSatisfy(fact -> assertThat(fact).isNotBlank().doesNotContain("null"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aztec-fire", "frost-crown", "inferno-riches", "jade-tiger",
            "gilded-cascade", "dragon-hoard"})
    void signatureMechanicsAreListedFirst(String gameId) {
        List<GameFeature> features = GameFeatureFactory.forSlot(math(gameId));
        int lastHeadline = -1;
        for (int i = 0; i < features.size(); i++) {
            if (features.get(i).headline()) lastHeadline = i;
        }
        long headlines = features.stream().filter(GameFeature::headline).count();
        assertThat(lastHeadline + 1)
                .as("%s: every headline feature must precede every non-headline one", gameId)
                .isEqualTo((int) headlines);
        // The lobby card chips the headline set; more than a couple would crowd the card.
        assertThat(headlines).as("%s headline count", gameId).isLessThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------- the quoted numbers

    @Test
    void theCascadeCardQuotesTheConfiguredLadderAndBound() {
        SlotMathDefinition math = math("gilded-cascade");
        GameFeature cascades = byKey(math).get("CASCADES");

        assertThat(cascades.headline()).isTrue();
        assertThat(cascades.facts())
                .anySatisfy(f -> assertThat(f).isEqualTo("Multiplier ladder: 1× → 2× → 3× → 5× → 10×"))
                .anySatisfy(f -> assertThat(f)
                        .isEqualTo("Up to " + math.cascades().maxCascades() + " tumbles in one spin"));
    }

    @Test
    void theJackpotCardMirrorsEveryTierAndNamesTheFullGridOne() {
        SlotMathDefinition math = math("dragon-hoard");
        int cells = math.grid().rows() * math.grid().cols();
        GameFeature jackpots = byKey(math).get("JACKPOTS");

        assertThat(jackpots.facts()).hasSameSizeAs(math.respins().jackpots());
        // Listed in the order a player climbs them, not the descending order the config is held in.
        assertThat(jackpots.facts()).containsExactly(
                "MINI - 20× at 6 coins",
                "MINOR - 60× at 9 coins",
                "MAJOR - 250× at 12 coins",
                "GRAND - 2,000× for a full " + cells + "-cell grid");
    }

    @Test
    void theHoldSpinCardQuotesTheTriggerRespinsAndCoinRange() {
        SlotMathDefinition math = math("dragon-hoard");
        GameFeature holdSpin = byKey(math).get("HOLD_SPIN");

        assertThat(holdSpin.headline()).isTrue();
        assertThat(holdSpin.facts())
                .anySatisfy(f -> assertThat(f).isEqualTo("Triggered by "
                        + math.respins().triggerMinCount() + "+ coins on the base grid"))
                .anySatisfy(f -> assertThat(f).contains(math.respins().respinsAwarded() + " respins"))
                // Read off the configured ladder, so widening it must widen the advertised range.
                .anySatisfy(f -> assertThat(f).isEqualTo(
                        "Coin values 1× - 25× the stake, drawn from a weighted ladder"));
    }

    @Test
    void aWaysGameLeadsWithItsWinModelAndAPaylineGameDoesNot() {
        SlotMathDefinition ways = math("jade-tiger");
        assertThat(ways.winModel()).isEqualTo(WinModel.WAYS);
        GameFeature waysCard = byKey(ways).get("WIN_MODEL");
        assertThat(waysCard.name()).isEqualTo("243 Ways to Win");
        assertThat(waysCard.headline()).isTrue();

        GameFeature lines = byKey(math("aztec-fire")).get("WIN_MODEL");
        assertThat(lines.name()).isEqualTo("20 Paylines");
        assertThat(lines.headline())
                .as("fixed paylines are the assumption a player already brings; they do not lead")
                .isFalse();
    }

    @Test
    void wildCardNamesOnlyTheBehavioursTheGameActuallyTurnsOn() {
        assertThat(byKey(math("gilded-cascade")).get("WILDS").name()).isEqualTo("Expanding Wilds");
        assertThat(byKey(math("dragon-hoard")).get("WILDS").name()).isEqualTo("Sticky & Walking Wilds");
    }

    @Test
    void buyPricesAreQuotedPerConfiguredOption() {
        assertThat(byKey(math("dragon-hoard")).get("BONUS_BUY").facts())
                .contains("Free Spins - 100× the stake", "Hold & Spin - 230.76× the stake");
    }

    /** All six slots ship, so nothing here may depend on a game the loader cannot find. */
    @Test
    void everyRegisteredSlotLoads() {
        assertThat(SLOTS).allSatisfy(id -> assertThat(math(id)).isNotNull());
    }
}
