package com.velocity.rgs.slot.feature.pickcollect;

import com.velocity.rgs.slot.math.domain.PickTileType;

import java.math.BigDecimal;

/**
 * Single resolved tile on the Pick &amp; Collect board. Generated once at feature start and frozen for
 * the duration of the feature (Section 5 Implementation Notes). The {@code value} is meaningful only
 * for {@link PickTileType#CREDITS} (bet multiplier) and {@link PickTileType#MULTIPLIER} (running
 * multiplier coefficient); ignored otherwise.
 *
 * <p>NEVER serialized to the client unless explicitly revealed via {@link PickCollectState} player view.
 */
public record PickCollectTile(PickTileType type, BigDecimal value) {
}
