package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.catalog.BetConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.session.domain.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotMathLoaderTest {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .registerModule(new ParameterNamesModule());

    private final SlotMathLoader loader = new SlotMathLoader();

    @Test
    void loadsReferenceAztecFireFixture() {
        GameDefinition game = loader.load("aztec-fire", "v1");

        assertThat(game.gameId()).isEqualTo("aztec-fire");
        assertThat(game.mathVersion()).isEqualTo("v1");

        // Presentation travels in the same game file as the math.
        GamePresentation presentation = game.presentation();
        assertThat(presentation.title()).isEqualTo("Aztec Fire");
        assertThat(presentation.theme()).isEqualTo("fire");
        assertThat(presentation.symbols().get(9).name()).isEqualTo("Wild");

        SlotMathDefinition def = game.math();
        assertThat(def.gameId()).isEqualTo("aztec-fire");
        assertThat(def.mathVersion()).isEqualTo("v1");
        assertThat(def.grid().rows()).isEqualTo(3);
        assertThat(def.grid().cols()).isEqualTo(5);
        assertThat(def.paylines()).hasSize(20);
        assertThat(def.reelStrips())
                .containsKeys(ReelStripSet.BASE, ReelStripSet.POWER_BET, ReelStripSet.FREE_SPINS);
        assertThat(def.reelStrips().get(ReelStripSet.BASE)).hasSize(5);
        def.reelStrips().values().forEach(strips ->
                strips.forEach(strip -> assertThat(strip.length()).isGreaterThanOrEqualTo(30)));
        // Free Spins is the only purchasable feature; Pick & Collect is organic-trigger-only.
        assertThat(def.bonusBuyOptions()).hasSize(1);
        assertThat(def.bonusBuyOptions().get(0).targetState()).isEqualTo(GameState.FREE_SPINS_AWAITING);
        assertThat(def.pickCollect().organicTriggerEnabled()).isTrue();
        assertThat(def.limits().maxWinPerRoundMultiplier()).isEqualTo(10000);

        // Bet config: server-driven stakes, default within the list, derived bounds, scale-insensitive match.
        BetConfig bet = def.betConfig();
        assertThat(bet.defaultBet()).isEqualByComparingTo("1.00");
        assertThat(bet.minBet()).isEqualByComparingTo("0.20");
        assertThat(bet.maxBet()).isEqualByComparingTo("100.00");
        assertThat(bet.isValidBet(new java.math.BigDecimal("1.0"))).isTrue();
        assertThat(bet.isValidBet(new java.math.BigDecimal("0.30"))).isFalse();
    }

    @Test
    void rejectsDefaultBetOutsideValues() {
        // defaultBet must be one of the configured stakes - compact-constructor validation must trip.
        assertThatThrownBy(() -> new BetConfig(
                java.util.List.of(new java.math.BigDecimal("0.20"), new java.math.BigDecimal("1.00")),
                new java.math.BigDecimal("0.50")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultBet");
    }

    @Test
    void failsFastOnMissingFile() {
        assertThatThrownBy(() -> loader.load("does-not-exist", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void failsFastOnHeaderMismatch() {
        assertThatThrownBy(() -> loader.load("aztec-fire", "v2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownTopLevelField() throws Exception {
        ObjectNode root;
        try (InputStream in = new ClassPathResource("games/aztec-fire/v1.json").getInputStream()) {
            root = (ObjectNode) STRICT.readTree(in);
        }
        ObjectNode math = (ObjectNode) root.get("math");
        math.put("extraThing", true);
        String json = STRICT.writeValueAsString(math);

        assertThatThrownBy(() -> STRICT.readValue(json, SlotMathDefinition.class))
                .hasMessageContaining("extraThing");
    }

    @Test
    void rejectsBadStructuralInvariants() {
        // payline has 4 coords, grid expects 5 → compact-constructor validation must trip.
        String json = """
                {
                  "gameId": "aztec-fire",
                  "mathVersion": "v1",
                  "targetRtp": 96.0,
                  "grid": { "rows": 3, "cols": 5 },
                  "symbols": [
                    { "id": 1, "name": "ACE", "type": "STANDARD" },
                    { "id": 9, "name": "WILD", "type": "WILD", "substitutes": "STANDARD" },
                    { "id": 12, "name": "SCATTER", "type": "SCATTER" }
                  ],
                  "paylines": [ { "id": 1, "coords": [[0,0],[0,1],[0,2],[0,3]] } ],
                  "payTable": { "1": { "3": 5 } },
                  "reelStrips": {
                    "BASE":       [[1],[1],[1],[1],[1]],
                    "POWER_BET":  [[1],[1],[1],[1],[1]],
                    "FREE_SPINS": [[1],[1],[1],[1],[1]]
                  },
                  "scatterTriggers": { "minCount": 3, "freeSpinsAwarded": 10, "retriggerAwards": 5 },
                  "freeSpins": { "betLockedToTriggerBet": true, "powerBetPersists": false, "maxRetriggerStack": 50 },
                  "powerBet": { "betMultiplier": 1.5 },
                  "bonusBuyOptions": [],
                  "pickCollect": {
                    "boardSize": 12,
                    "completion": { "type": "FIXED_PICKS", "value": 5 },
                    "tileDistribution": [ { "type": "BLANK", "weight": 10 } ],
                    "maxFeatureWinMultiplier": 5000
                  },
                  "limits": { "maxWinPerRoundMultiplier": 10000 },
                  "betConfig": { "values": [1.0], "defaultBet": 1.0 }
                }
                """;
        assertThatThrownBy(() -> STRICT.readValue(json, SlotMathDefinition.class))
                .hasMessageContaining("payline");
    }
}
