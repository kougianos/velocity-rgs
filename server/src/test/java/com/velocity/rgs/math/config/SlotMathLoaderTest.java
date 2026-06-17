package com.velocity.rgs.math.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.velocity.rgs.math.domain.ReelStripSet;
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
        SlotMathDefinition def = loader.load("aztec-fire", "v1");

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
        assertThat(def.bonusBuyOptions()).hasSize(2);
        assertThat(def.bonusBuyOptions().get(0).targetState()).isEqualTo(GameState.FREE_SPINS_AWAITING);
        assertThat(def.limits().maxWinPerRoundMultiplier()).isEqualTo(10000);
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
        ObjectNode tree;
        try (InputStream in = new ClassPathResource("math/aztec-fire/v1.json").getInputStream()) {
            tree = (ObjectNode) STRICT.readTree(in);
        }
        tree.put("extraThing", true);
        String json = STRICT.writeValueAsString(tree);

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
                  "limits": { "maxWinPerRoundMultiplier": 10000 }
                }
                """;
        assertThatThrownBy(() -> STRICT.readValue(json, SlotMathDefinition.class))
                .hasMessageContaining("payline");
    }
}
