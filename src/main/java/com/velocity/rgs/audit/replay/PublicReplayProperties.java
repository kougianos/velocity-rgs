package com.velocity.rgs.audit.replay;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for public, shareable round-replay links (§3.1).
 *
 * <p>The links are stateless - there is no table of issued links and nothing to clean up. That is a
 * deliberate trade: a signed token cannot be revoked individually, only by rotating
 * {@code rgs.security.jwt-secret}, which invalidates every outstanding link at once. For a proof link
 * with a short life that is the right side of the trade; a link that had to be revocable per-round would
 * need to be a database row instead.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.replay")
public class PublicReplayProperties {

    /**
     * How long a freshly minted public replay link stays valid. Short enough that a link pasted into a
     * public place stops working, long enough to survive being emailed and opened the next morning.
     */
    private Duration publicLinkTtl = Duration.ofHours(24);
}
