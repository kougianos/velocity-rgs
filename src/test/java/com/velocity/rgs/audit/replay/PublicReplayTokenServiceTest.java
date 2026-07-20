package com.velocity.rgs.audit.replay;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.SecurityProperties;
import com.velocity.rgs.testsupport.JwtTestFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security properties of a public replay link, asserted directly rather than inferred from the
 * endpoint - these are the claims the feature is sold on, so they get tested where they are implemented.
 */
class PublicReplayTokenServiceTest {

    private static final String ROUND = "rnd-1c9f0a";

    private static PublicReplayTokenService service(Duration ttl) {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(JwtTestFactory.SECRET);
        PublicReplayProperties replay = new PublicReplayProperties();
        replay.setPublicLinkTtl(ttl);
        return new PublicReplayTokenService(security, replay);
    }

    private static PublicReplayTokenService service() {
        return service(Duration.ofHours(24));
    }

    @Test
    void mintedTokenVerifiesBackToItsOwnRound() {
        PublicReplayTokenService service = service();

        PublicReplayTokenService.SignedReplayLink link = service.mint(ROUND);
        PublicReplayTokenService.VerifiedReplayLink verified = service.verify(link.token());

        assertThat(verified.roundId()).isEqualTo(ROUND);
        assertThat(link.ttlSeconds()).isEqualTo(Duration.ofHours(24).toSeconds());
        assertThat(link.expiresAt()).isAfter(Instant.now());
        assertThat(verified.expiresAt()).isCloseTo(link.expiresAt(), org.assertj.core.api.Assertions.within(
                1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void everyLinkCarriesOnlyItsOwnRound() {
        PublicReplayTokenService service = service();

        assertThat(service.verify(service.mint("round-a").token()).roundId()).isEqualTo("round-a");
        assertThat(service.verify(service.mint("round-b").token()).roundId()).isEqualTo("round-b");
    }

    @Test
    void expiredLinkIsReportedAsExpiredRatherThanInvalid() {
        // The distinction is the whole reason REPLAY_LINK_EXPIRED exists: "this ran out" is a different
        // thing to tell a visitor than "this was tampered with", and the page says so.
        PublicReplayTokenService service = service(Duration.ofSeconds(-30));

        String token = service.mint(ROUND).token();

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPLAY_LINK_EXPIRED);
    }

    @Test
    void claimsFromOneLinkCannotBeWornWithAnotherLinksSignature() {
        PublicReplayTokenService service = service();
        String[] a = service.mint("round-a").token().split("\\.");
        String[] b = service.mint("round-b").token().split("\\.");

        // round-a's header and claims, round-b's signature - the shape a forgery attempt actually takes.
        String forged = a[0] + "." + a[1] + "." + b[2];

        assertThatThrownBy(() -> service.verify(forged))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPLAY_LINK_INVALID);
    }

    /**
     * The key-separation claim. Both token families are seeded by {@code rgs.security.jwt-secret}, but a
     * replay link is signed with a key derived from it for that purpose alone - so a player's JWT, which
     * verifies perfectly well in {@code JwtAuthenticationFilter}, is not a replay link. The converse
     * (a replay token presented as a bearer credential) is asserted in
     * {@link PublicReplayIntegrationTest}.
     */
    @Test
    void aPlayerJwtIsNotAReplayLink() {
        PublicReplayTokenService service = service();
        String playerToken = JwtTestFactory.adminToken("p-admin");

        assertThatThrownBy(() -> service.verify(playerToken))
                .isInstanceOf(RgsException.class)
                .extracting(e -> ((RgsException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPLAY_LINK_INVALID);
    }

    @Test
    void garbageAndEmptyTokensAreRejected() {
        PublicReplayTokenService service = service();

        for (String bad : new String[]{"", "   ", "not-a-token", "a.b.c"}) {
            assertThatThrownBy(() -> service.verify(bad))
                    .isInstanceOf(RgsException.class)
                    .extracting(e -> ((RgsException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REPLAY_LINK_INVALID);
        }
    }
}
