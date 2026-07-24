package com.velocity.rgs.rg;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link RgPolicyProperties} binding (§4.2).
 */
@Configuration
@EnableConfigurationProperties(RgPolicyProperties.class)
public class RgConfig {
}
