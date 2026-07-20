package com.velocity.rgs.audit.replay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PublicReplayProperties.class)
public class PublicReplayConfiguration {
}
