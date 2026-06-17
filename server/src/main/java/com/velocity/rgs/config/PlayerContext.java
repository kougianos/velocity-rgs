package com.velocity.rgs.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Request-scoped principal extracted from the validated JWT.
 */
@Getter
@Setter
public class PlayerContext {

    private String playerId;
    private String sessionId;
    private String currency;
    private List<String> roles = List.of();
    private boolean authenticated;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
