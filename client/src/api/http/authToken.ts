/**
 * In-memory JWT holder consumed by the axios request interceptor. M2's
 * `authStore` (Zustand) is the authoritative source and is responsible for
 * pushing the latest token into this holder via {@link setAuthToken}.
 *
 * This lives under `src/api/http/` rather than `src/auth/` so the axios
 * instance can depend on it without violating the
 * `src/api/** → src/auth/**` directionality (no inverse import).
 */
let currentToken: string | null = null;

export function getAuthToken(): string | null {
  return currentToken;
}

export function setAuthToken(token: string | null): void {
  currentToken = token;
}

export function clearAuthToken(): void {
  currentToken = null;
}
