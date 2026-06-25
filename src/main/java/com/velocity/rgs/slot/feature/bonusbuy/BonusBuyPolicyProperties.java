package com.velocity.rgs.slot.feature.bonusbuy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Server-side policy gates for Bonus Buy entry (Section 4 - Implementation Notes / M5 Task 5.6).
 * Sourced from {@code rgs.bonus-buy.*}; defaults declared in {@code application.yml}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.bonus-buy")
public class BonusBuyPolicyProperties {

    /** Global on/off switch for the Bonus Buy feature (overrides per-game JSON if false). */
    private boolean enabled = true;

    /** Allowed jurisdictions (ISO-3166 codes). Empty list = no jurisdiction restriction. */
    private List<String> allowedJurisdictions = List.of();

    /** Minimum player balance required to attempt a Bonus Buy. */
    private BigDecimal minimumBalance = BigDecimal.ZERO;
}
