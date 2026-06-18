import { env } from '@/env';

const STORAGE_KEY = 'rgs.auth.token';

/**
 * Persistent token storage adapter. `sessionStorage` is used only when
 * `VITE_AUTH_STORAGE === 'session'`; otherwise the token lives in memory only
 * for the lifetime of the tab and is lost on reload. `localStorage` is NEVER
 * an option (frontend rule Q6).
 */
export function loadPersistedToken(): string | null {
  if (env.VITE_AUTH_STORAGE !== 'session') return null;
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function persistToken(token: string): void {
  if (env.VITE_AUTH_STORAGE !== 'session') return;
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(STORAGE_KEY, token);
  } catch {
    /* sessionStorage unavailable (private mode, quota); silently fall back to memory */
  }
}

export function clearPersistedToken(): void {
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}
