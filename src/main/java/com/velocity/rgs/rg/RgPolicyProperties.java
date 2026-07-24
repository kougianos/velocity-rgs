package com.velocity.rgs.rg;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * The Responsible Gaming ruleset (§4.2), from {@code rgs.rg.*}.
 *
 * <p>Deliberately jurisdiction-neutral. With no licence to build against, guessing at MGA or UKGC
 * specifics would encode someone's half-remembered version of a real rulebook; a generic ruleset behind
 * the same seam is honest about what it is, and a real one drops in later without touching the money
 * path.
 *
 * <p>The defaults are <em>demo</em> defaults, and tight on purpose: a loss limit reachable in about ten
 * spins and a reality check on a two-minute interval. A ruleset whose limits take an hour to reach
 * cannot be shown to anyone, and a limit nobody has watched fire is a limit nobody believes works.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.rg")
public class RgPolicyProperties {

    /** Master switch. Off means no limit is enforced and no block applies, for a clean demo reset. */
    private boolean enabled = true;

    /** Limits applied to a player who has set none of their own. Null entries mean "no default". */
    private Defaults defaults = new Defaults();

    /** Ceilings on what a player may set, so the panel cannot be used to opt out of the ruleset. */
    private Bounds bounds = new Bounds();

    /**
     * Idle time after which the next staked action starts a new play session, resetting the clock the
     * session-duration limit runs against. Without this a player who stops for the night comes back to
     * an already-exhausted session limit.
     */
    private int sessionIdleResetMinutes = 30;

    @Getter
    @Setter
    public static class Defaults {
        private Integer sessionLimitMinutes = 60;
        private BigDecimal lossLimit = new BigDecimal("50.00");
        private BigDecimal wagerLimit = new BigDecimal("500.00");
        private Integer realityCheckMinutes = 2;
    }

    /**
     * A player may always make a limit <em>stricter</em>; these cap how loose it can be made. The
     * asymmetry is the point - self-protection is never refused, opting out is.
     */
    @Getter
    @Setter
    public static class Bounds {
        private int maxSessionLimitMinutes = 24 * 60;
        private BigDecimal maxLossLimit = new BigDecimal("10000.00");
        private BigDecimal maxWagerLimit = new BigDecimal("100000.00");
        private int maxRealityCheckMinutes = 120;
        private int maxCoolOffHours = 30 * 24;
    }
}
