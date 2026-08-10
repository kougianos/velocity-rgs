package com.velocity.rgs.jackpot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link JackpotProperties} binding (§2).
 */
@Configuration
@EnableConfigurationProperties(JackpotProperties.class)
public class JackpotConfig {
}
