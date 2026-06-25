package com.velocity.rgs.wallet.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Player authentication / wallet eligibility check (A.0.1 - first call in init/resume).
 * <p>
 * In the {@code demo} profile this is effectively a no-op beyond JWT validation,
 * because authentication is already enforced by {@code JwtAuthenticationFilter}.
 */
public record WalletAuthenticateRequest(@NotBlank String playerId) {
}
