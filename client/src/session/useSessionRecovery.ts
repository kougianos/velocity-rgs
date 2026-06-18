import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import { RgsHttpError, RgsNetworkError } from '@/api/http/errors';
import { useAuthStore } from '@/auth/authStore';
import { pushToast } from '@/ui/toast/toastStore';

import { subscribeRgsError } from './errorBus';
import { useSessionStore } from './sessionStore';

/**
 * Mount-once global error listener (Task 2.5). On `SESSION_VERSION_CONFLICT`
 * or `SESSION_NOT_FOUND` it resets the session mirror and re-invalidates the
 * cached `/init` query so {@link useSessionInit} re-fires automatically.
 * Other domain errors surface the canonical Appendix D toast.
 */
export function useSessionRecovery(): void {
  const queryClient = useQueryClient();
  const resetSession = useSessionStore((s) => s.reset);
  const clearAuth = useAuthStore((s) => s.clear);

  useEffect(() => {
    const handler = (err: unknown): void => {
      if (err instanceof RgsHttpError) {
        switch (err.code) {
          case 'SESSION_VERSION_CONFLICT':
          case 'SESSION_NOT_FOUND': {
            resetSession();
            void queryClient.invalidateQueries({ queryKey: ['init'] });
            pushToast('warn', 'Session refreshed — try again.', { traceId: err.traceId });
            return;
          }
          case 'AUTH_FAILED': {
            clearAuth();
            resetSession();
            pushToast('warn', 'Your session expired. Please sign in again.');
            return;
          }
          case 'ILLEGAL_STATE_TRANSITION': {
            void queryClient.invalidateQueries({ queryKey: ['init'] });
            pushToast('warn', "That action isn't allowed right now. Refreshing your game…", {
              traceId: err.traceId,
            });
            return;
          }
          default:
            return;
        }
      }
      if (err instanceof RgsNetworkError) {
        pushToast('warn', 'Connection lost. Retrying…');
      }
    };

    const unsubscribe = subscribeRgsError(handler);
    return unsubscribe;
  }, [queryClient, resetSession, clearAuth]);
}
