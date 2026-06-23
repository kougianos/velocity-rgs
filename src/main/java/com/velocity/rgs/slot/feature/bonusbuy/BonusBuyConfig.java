package com.velocity.rgs.slot.feature.bonusbuy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link BonusBuyPolicyProperties} binding (Section 4 / M5 Task 5.6).
 */
@Configuration
@EnableConfigurationProperties(BonusBuyPolicyProperties.class)
public class BonusBuyConfig {
}
