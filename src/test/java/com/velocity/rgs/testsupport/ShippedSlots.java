package com.velocity.rgs.testsupport;

import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathLoader;
import com.velocity.rgs.slot.math.domain.BonusBuyType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The slot games this build actually ships, read from {@code rgs.math.catalog} in
 * {@code application.yml} - the same list {@code MathCatalogProperties} loads at startup.
 *
 * <p>Exists so the RTP guards enumerate their games instead of naming them. A hand-maintained
 * {@code @ValueSource} is a list that has to be updated by whoever adds a game, and twice it was not:
 * gilded-cascade and dragon-hoard shipped a purchasable free-spins feature that no guard ever measured,
 * and both were wrong - one paying 540% of its declared RTP, the other 76%. Neither was noticed for as
 * long as the games existed, because the base-game guard <em>did</em> list them and passed.
 *
 * <p>Deriving the list is deliberately stronger than testing it. A test asserting "the guard's list
 * matches the catalog" is one more thing to forget to write; a guard that reads the catalog cannot drift
 * from it at all. Ship a game and it is measured, with no step in between that depends on remembering.
 */
public final class ShippedSlots {

    private static final String MATH_VERSION = "v1";

    private ShippedSlots() {
    }

    /** Every slot game id in the shipped catalog, in declaration order. */
    @SuppressWarnings("unchecked")
    public static List<String> all() {
        try (InputStream yaml = ShippedSlots.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(yaml);
            Map<String, Object> rgs = (Map<String, Object>) root.get("rgs");
            Map<String, Object> math = (Map<String, Object>) rgs.get("math");
            List<Map<String, Object>> catalog = (List<Map<String, Object>>) math.get("catalog");

            List<String> ids = catalog.stream().map(e -> String.valueOf(e.get("gameId"))).toList();
            if (ids.isEmpty()) {
                throw new IllegalStateException("rgs.math.catalog is empty");
            }
            return ids;
        } catch (Exception ex) {
            // Never swallowed into an empty list: an empty @MethodSource would make every guard below
            // pass without simulating a single spin, which is the one failure mode a guard must not have.
            throw new IllegalStateException("cannot read rgs.math.catalog from application.yml", ex);
        }
    }

    /**
     * The shipped games offering the given purchasable feature - the games whose buy channel a guard
     * has to cover, derived from the math rather than from a list someone maintains.
     */
    public static List<String> offering(BonusBuyType buyType) {
        List<String> ids = new ArrayList<>();
        for (String gameId : all()) {
            if (math(gameId).bonusBuyOptions().stream().anyMatch(o -> o.buyType() == buyType)) {
                ids.add(gameId);
            }
        }
        return List.copyOf(ids);
    }

    /** The shipped math for one game, at the catalog's version. */
    public static SlotMathDefinition math(String gameId) {
        return new SlotMathLoader().load(gameId, MATH_VERSION).math();
    }

    public static String mathVersion() {
        return MATH_VERSION;
    }
}
