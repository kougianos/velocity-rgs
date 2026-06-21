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

    public static final String ATTR_PLAYER_ID = "rgs.auth.playerId";
    public static final String ATTR_SESSION_ID = "rgs.auth.sessionId";
    public static final String ATTR_CURRENCY = "rgs.auth.currency";
    public static final String ATTR_ROLES = "rgs.auth.roles";
    public static final String ATTR_AUTHENTICATED = "rgs.auth.authenticated";

    private String playerId;
    private String sessionId;
    private String currency;
    private List<String> roles = List.of();
    private boolean authenticated;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
