import { useMutation, type UseMutationResult } from '@tanstack/react-query';
import { useRef } from 'react';

import { RgsNetworkError } from '@/api/http/errors';
import { spin, type SpinResponse } from '@/api/slot/spin';
import { newIdempotencyKey } from '@/common/idempotency/key';
import { useSessionStore } from '@/session/sessionStore';

export interface UseSpinInput {
  powerBetActive: boolean;
}

export type UseSpinResult = UseMutationResult<SpinResponse, Error, UseSpinInput>;

/**
 * Mutation hook wrapping `POST /api/v1/slot/spin`. Honors the
 * idempotency-key lifecycle from Appendix F: one key per user intent,
 * reused on transport failure, cleared on any definitive HTTP response.
 *
 * Session fields are read from `useSessionStore.getState()` at invocation
 * time so the latest `sessionVersion` and `currentBet` are always sent
 * (mirror rule §2.3).
 */
export function useSpin(): UseSpinResult {
  const keyRef = useRef<string | null>(null);
  const applySpinResponse = useSessionStore((s) => s.applySpinResponse);

  return useMutation<SpinResponse, Error, UseSpinInput>({
    mutationFn: async ({ powerBetActive }) => {
      const { sessionId, sessionVersion, gameId, currentBet } = useSessionStore.getState();
      if (
        sessionId === null ||
        sessionVersion === null ||
        gameId === null ||
        currentBet === null
      ) {
        throw new Error('Session is not initialised; cannot spin.');
      }
      if (keyRef.current === null) {
        keyRef.current = newIdempotencyKey();
      }
      return spin(keyRef.current, {
        gameId,
        sessionId,
        sessionVersion,
        betSize: currentBet.toPlain(),
        powerBetActive,
      });
    },
    retry: 0,
    onSuccess: (resp) => {
      applySpinResponse(resp);
      keyRef.current = null;
    },
    onError: (err) => {
      // Transport-level failure keeps the key alive for the next retry of
      // the same user intent. Any definitive HTTP response (4xx/5xx) closes
      // the intent and frees the key.
      if (!(err instanceof RgsNetworkError)) {
        keyRef.current = null;
      }
    },
  });
}
