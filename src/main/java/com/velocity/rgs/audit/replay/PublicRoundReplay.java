package com.velocity.rgs.audit.replay;

import com.velocity.rgs.rng.RngDraw;
import com.velocity.rgs.slot.math.engine.CascadeStep;
import com.velocity.rgs.slot.math.engine.WinLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The redacted view of a replayed round served to an anonymous holder of a public link (§3.1).
 *
 * <p>Deliberately <b>not</b> {@link RoundReplayResult}: that record carries {@code playerId} and
 * {@code sessionId}, and a link forwarded to a stranger must not disclose who played the round or let
 * them correlate it with any other. What survives here is only the round's own mechanics - the maths, the
 * money it staked and paid, and the draws that produced it.
 *
 * <p>Both boards are carried per step: {@code persistedGrid} is what was written at round commit,
 * {@code grid} is what the engine just rebuilt from {@link #rngDraws}. The server has already refused to
 * serve a round whose sequence diverged, so a viewer is not being asked to take {@code matches} on trust
 * - they can read the two grids off the page and check the claim themselves. That is the entire point of
 * a public proof link, and it is why the draws ship alongside rather than being summarised away.
 *
 * @param carriedWildMode  {@code WALKING} or {@code STICKY} when this round's wilds carry between spins,
 *                         null otherwise. A round can have a mode and an empty {@code carriedWildCells}:
 *                         that is the first spin of the feature, and the round recording that it started
 *                         from a clean board is as much a part of the proof as recording three wilds
 * @param carriedWildCells {@code [row, col]} of the wilds this spin did not draw but inherited from the
 *                         spin before it, already stepped left where they walk. Empty for every round
 *                         without such a carry. Shown rather than summed away because a walking wild is
 *                         the one mechanic on these games whose board depends on an earlier round, and
 *                         the whole claim of the page is that the dependency is recorded rather than
 *                         assumed - a viewer should be able to see which cells it accounts for
 * @param featureWinBeforeThisSpin what earlier spins of the same free-spins feature had won when this
 *                         one began, with {@code featureBuyMultiplier} the boost a bonus buy attached.
 *                         Non-null only on the spin that settled a feature - the one round whose payout
 *                         is not what its own board produced. Published so the page can show the sum
 *                         rather than assert a figure the visible board plainly does not add up to
 * @param verifiedAt       when this reconstruction ran - request time, not round time. The round is
 *                         re-derived on every view; nothing here is a cached verdict
 * @param linkExpiresAt    when the link that served this stops working
 */
public record PublicRoundReplay(
        String roundId,
        String gameId,
        String mathVersion,
        String reelStripSet,
        boolean powerBetActive,
        String currency,
        BigDecimal betAmount,
        BigDecimal originalTotalWin,
        BigDecimal reconstructedTotalWin,
        boolean matrixMatches,
        boolean totalWinMatches,
        int stepCount,
        List<Step> steps,
        List<RngDraw> rngDraws,
        String carriedWildMode,
        List<int[]> carriedWildCells,
        BigDecimal featureWinBeforeThisSpin,
        BigDecimal featureBuyMultiplier,
        Instant roundPlayedAt,
        Instant verifiedAt,
        Instant linkExpiresAt
) {

    /**
     * One drop of the round as rebuilt, next to the board that was persisted for it.
     *
     * @param stopPositions     reel stops for the opening drop; for a refill, the strip indices drawn per
     *                          refilled cell in draw order (see {@link CascadeStep})
     * @param clearedPositions  {@code [row, col]} pairs this step's wins removed before the next drop.
     *                          Empty on a settled board and on games that do not cascade; where it is
     *                          populated it lets the page mark the exact cells that paid without
     *                          re-deriving them client-side from paylines or ways paths
     * @param matches           whether this step's rebuilt board equals the persisted one
     */
    public record Step(
            int index,
            int[][] grid,
            int[][] persistedGrid,
            int[] stopPositions,
            int[][] clearedPositions,
            List<WinLine> winLines,
            BigDecimal multiplier,
            BigDecimal stepWin,
            boolean matches
    ) {}

    static PublicRoundReplay from(RoundReplayResult result, Instant linkExpiresAt) {
        List<CascadeStep> rebuilt = result.reconstructedSequence();
        List<int[][]> persisted = result.originalSequence();

        List<Step> steps = new ArrayList<>(rebuilt.size());
        for (int i = 0; i < rebuilt.size(); i++) {
            CascadeStep step = rebuilt.get(i);
            // A round whose sequence lengths diverged never reaches here - ReplayService throws first -
            // but this record is a public surface, so it indexes defensively rather than assuming.
            int[][] persistedGrid = i < persisted.size() ? persisted.get(i) : null;
            steps.add(new Step(
                    step.index(),
                    step.grid(),
                    persistedGrid,
                    step.stopPositions(),
                    step.clearedPositions(),
                    step.winLines(),
                    step.multiplier(),
                    step.stepWin(),
                    persistedGrid != null && Arrays.deepEquals(persistedGrid, step.grid())
            ));
        }

        return new PublicRoundReplay(
                result.roundId(),
                result.gameId(),
                result.mathVersion(),
                result.reelStripSet(),
                result.powerBetActive(),
                result.currency(),
                result.betAmount(),
                result.originalTotalWin(),
                result.reconstructedTotalWin(),
                result.matrixMatches(),
                result.totalWinMatches(),
                steps.size(),
                List.copyOf(steps),
                result.rngDraws(),
                result.carriedWildMode(),
                result.carriedWildCells(),
                result.featureWinBeforeThisSpin(),
                result.featureBuyMultiplier(),
                result.originalCreatedAt(),
                Instant.now(),
                linkExpiresAt
        );
    }
}
