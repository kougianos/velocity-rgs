package com.velocity.rgs.testsupport;

import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.session.service.SessionStore;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test helper for the (now organic, no longer buyable) Pick &amp; Collect feature.
 *
 * <p>In production the feature is entered only via the random in-spin trigger
 * ({@code pickCollect.triggerOneInN}), which can't be forced deterministically from an HTTP test.
 * Tests that exercise the {@code /feature/start} → {@code /feature/pick} flow therefore drop the
 * session straight into {@code PICK_COLLECT_AWAITING} here — the same state the live trigger produces —
 * then drive the public endpoints from there.
 */
public final class PickCollectTestSupport {

    private PickCollectTestSupport() {
    }

    /**
     * Forces the given session into {@code PICK_COLLECT_AWAITING} (as the organic trigger would) and
     * returns the new session version to use on the next request.
     */
    public static long forcePickCollectAwaiting(SessionStore sessionStore, String sessionId) {
        GameSession session = sessionStore.requireBySessionId(sessionId);
        session.setCurrentState(GameState.PICK_COLLECT_AWAITING);
        session.setActiveFeaturePayload("{\"boardSize\":12,\"trigger\":\"ORGANIC\"}");
        session.setNextActionAllowed(GameCommand.START_PICK_COLLECT.name());
        session.setRemainingFreeSpins(0);
        session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
        session.setCurrentBet(new BigDecimal("1.00"));
        session.setUpdatedAt(Instant.now());
        return sessionStore.save(session).getSessionVersion();
    }
}
