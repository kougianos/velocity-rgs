import { create } from 'zustand';

import { clearAuthToken, setAuthToken } from '@/api/http/authToken';
import type { Currency } from '@/common/money/Money';

import { clearPersistedToken, loadPersistedToken, persistToken } from './authStorage';
import { claimsExpiry, decodeJwtPayload, type JwtClaims } from './jwt';

export interface AuthState {
  token: string | null;
  playerId: string | null;
  sessionId: string | null;
  currency: Currency | null;
  roles: string[];
  expiresAt: Date | null;
}

export interface AuthStore extends AuthState {
  setToken: (token: string) => void;
  clear: () => void;
}

const emptyState: AuthState = {
  token: null,
  playerId: null,
  sessionId: null,
  currency: null,
  roles: [],
  expiresAt: null,
};

function hydrateFromClaims(token: string, claims: JwtClaims): AuthState {
  return {
    token,
    playerId: claims.sub,
    sessionId: claims.sid,
    currency: claims.cur,
    roles: claims.roles,
    expiresAt: claimsExpiry(claims),
  };
}

function bootstrapInitialState(): AuthState {
  const persisted = loadPersistedToken();
  if (!persisted) return emptyState;
  try {
    const claims = decodeJwtPayload(persisted);
    if (claims.exp * 1000 <= Date.now()) {
      clearPersistedToken();
      return emptyState;
    }
    setAuthToken(persisted);
    return hydrateFromClaims(persisted, claims);
  } catch {
    clearPersistedToken();
    return emptyState;
  }
}

export const useAuthStore = create<AuthStore>((set) => ({
  ...bootstrapInitialState(),
  setToken: (token: string) => {
    const claims = decodeJwtPayload(token);
    setAuthToken(token);
    persistToken(token);
    set(hydrateFromClaims(token, claims));
  },
  clear: () => {
    clearAuthToken();
    clearPersistedToken();
    set(emptyState);
  },
}));

export function selectIsAuthenticated(state: AuthState): boolean {
  return state.token !== null && state.expiresAt !== null && state.expiresAt.getTime() > Date.now();
}

export function selectHasRole(role: string) {
  return (state: AuthState): boolean => state.roles.includes(role);
}
