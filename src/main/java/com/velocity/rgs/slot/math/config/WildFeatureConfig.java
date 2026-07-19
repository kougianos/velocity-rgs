package com.velocity.rgs.slot.math.config;

import com.velocity.rgs.slot.math.domain.ReelStripSet;

import java.util.List;
import java.util.Set;

/**
 * Wild behaviours that reshape the grid <em>before</em> it is evaluated: expanding, sticky and walking
 * wilds. All three are config, not code - a game turns one on in {@code math.wildFeatures} and the
 * evaluator it uses never changes.
 *
 * <p>Deliberately a grid transform rather than an evaluator variant. Expanding a wild down its reel
 * means the same thing under paylines and under ways, so encoding it in either evaluator would mean
 * encoding it twice, and a cascading game would then have to decide which of the two applied on a
 * refill. Transforming the grid keeps all of that orthogonal: {@code WildFeatureEngine} rewrites the
 * board, and whatever evaluates it is none the wiser.
 *
 * @param expanding    a wild fills its entire reel. The classic "expanding wild" - it makes a single
 *                     wild worth a whole column of substitutions
 * @param sticky       a wild stays on the board for the following spins of the same feature (tracked in
 *                     {@code active_feature_payload}), rather than being re-drawn
 * @param walking      a sticky wild shifts one reel left on every subsequent spin until it walks off the
 *                     grid - the "walking wild" respin staple
 * @param stickySpins  how many further spins a sticky/walking wild survives
 * @param appliesTo    which strip sets the behaviours are live on. Restricting them to FREE_SPINS is the
 *                     conventional shape: the base game stays lean and the feature is where the wilds
 *                     misbehave. Empty means every strip set
 */
public record WildFeatureConfig(
        boolean expanding,
        boolean sticky,
        boolean walking,
        int stickySpins,
        Set<ReelStripSet> appliesTo
) {

    /** Wilds that only substitute, which is what every game authored before this block did. */
    public static WildFeatureConfig none() {
        return new WildFeatureConfig(false, false, false, 0, Set.of());
    }

    public WildFeatureConfig {
        appliesTo = appliesTo == null || appliesTo.isEmpty()
                ? Set.of(ReelStripSet.values())
                : Set.copyOf(appliesTo);
        if ((sticky || walking) && stickySpins < 1) {
            throw new IllegalArgumentException(
                    "wildFeatures.stickySpins must be >= 1 when wilds are sticky or walking, found "
                            + stickySpins);
        }
        if (walking && !sticky) {
            throw new IllegalArgumentException(
                    "wildFeatures.walking requires sticky: a wild has to persist before it can walk");
        }
    }

    /** Whether any behaviour at all is configured. */
    public boolean active() {
        return expanding || sticky || walking;
    }

    /** Whether the behaviours apply on the given strip set. */
    public boolean appliesOn(ReelStripSet stripSet) {
        return active() && appliesTo.contains(stripSet);
    }

    /** The strip sets as a stable, ordered list - for config echo and tests. */
    public List<ReelStripSet> appliesToOrdered() {
        return appliesTo.stream().sorted().toList();
    }
}
