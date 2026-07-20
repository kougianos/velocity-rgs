package com.velocity.rgs.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.security")
public class SecurityProperties {

    private String jwtSecret;
    private String jwtIssuer = "velocity-rgs";
    private List<String> corsAllowedOrigins = List.of("http://localhost:5173");
    private List<String> publicPaths = List.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            // Public round-replay links. Anonymous on purpose and in every run mode: the endpoint is
            // reached only with a signed token that names the single round it may read, so it stays in
            // the default list rather than being a demo-mode concession.
            "/api/v1/public/**"
    );
}
