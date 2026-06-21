package com.velocity.rgs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    @Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
    @SuppressWarnings("unchecked")
    public PlayerContext playerContext(HttpServletRequest request) {
        PlayerContext context = new PlayerContext();
        context.setPlayerId((String) request.getAttribute(PlayerContext.ATTR_PLAYER_ID));
        context.setSessionId((String) request.getAttribute(PlayerContext.ATTR_SESSION_ID));
        context.setCurrency((String) request.getAttribute(PlayerContext.ATTR_CURRENCY));
        Object roles = request.getAttribute(PlayerContext.ATTR_ROLES);
        if (roles instanceof List<?> list) {
            context.setRoles((List<String>) list);
        }
        Object authenticated = request.getAttribute(PlayerContext.ATTR_AUTHENTICATED);
        context.setAuthenticated(Boolean.TRUE.equals(authenticated));
        return context;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(SecurityProperties properties,
                                                           ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(properties, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(SecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.getCorsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Trace-Id"));
        cors.setExposedHeaders(List.of("X-Trace-Id", "Idempotent-Replay"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(source));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
