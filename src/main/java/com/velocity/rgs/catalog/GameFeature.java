package com.velocity.rgs.catalog;

import java.util.List;
import java.util.Objects;

/**
 * One advertised mechanic of a game, derived from the math config that actually drives the engine
 * rather than authored as marketing copy alongside it.
 *
 * <p>That derivation is the point. A game turns a mechanic on by adding its block to
 * {@code games/<gameId>/<mathVersion>.json}; the same block is what the lobby advertises, and the
 * numbers quoted here ({@code facts}) are read straight out of it. Remove {@code math.cascades} and the
 * "Cascading Reels" card disappears from the lobby in the same deploy that stops the reels tumbling -
 * the shop window cannot drift from the engine, because there is only one source for both.
 *
 * <p>Contrast with {@link GameInfo}, which is hand-authored presentation copy in the {@code
 * presentation.info} block: prose, spec sheet, the marketing voice. Both are served; the client shows
 * {@code features} first and {@code info} as the detail sheet behind it.
 *
 * @param key      stable machine key ({@code CASCADES}, {@code HOLD_SPIN}, …) so the client can tag,
 *                 order or icon a feature without parsing its name
 * @param name     display name, kept short enough to double as a card chip
 * @param icon     a single emoji, so the client needs no icon set
 * @param summary  what the mechanic does, in a sentence or two
 * @param facts    the configured numbers behind it - the multiplier ladder, the trigger count, the
 *                 jackpot tiers. Rendered as bullets
 * @param headline whether this mechanic is what <em>defines</em> the game, as opposed to one it merely
 *                 also has. The lobby chips these onto the card; at most a couple per game
 */
public record GameFeature(
        String key,
        String name,
        String icon,
        String summary,
        List<String> facts,
        boolean headline
) {

    public GameFeature {
        Objects.requireNonNull(key, "feature.key");
        Objects.requireNonNull(name, "feature.name");
        Objects.requireNonNull(summary, "feature.summary");
        icon = icon == null ? "" : icon;
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
