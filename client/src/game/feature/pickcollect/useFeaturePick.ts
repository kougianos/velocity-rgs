import { useMutation, type UseMutationResult } from '@tanstack/react-query';
import { useRef } from 'react';

import { RgsNetworkError } from '@/api/http/errors';
import { featurePick, type FeaturePickResponse } from '@/api/slot/featurePick';
import { newIdempotencyKey } from '@/common/idempotency/key';
import { useSessionStore } from '@/session/sessionStore';

export interface UseFeaturePickInput {
  position: number;
}

export type UseFeaturePickResult = UseMutationResult<
  FeaturePickResponse,
  Error,
  UseFeaturePickInput
>;

/**
 * Mutation hook wrapping `POST /api/v1/slot/feature/pick` (Task 7.5). Mirrors
 * the idempotency-key lifecycle from Appendix F: one key per user intent
 * (one tile click), reused on transport failure, cleared on any definitive
 * HTTP response.
 *
 * Hard guard (Task 7.8): if the requested `position` is already opened in
 * the local `activeFeatureView`, this hook short-circuits without firing a
 * network call. The server is still authoritative — a stale `openedPositions`
 * triggers an `ILLEGAL_STATE_TRANSITION` and re-`init`.
 */
export function useFeaturePick(): UseFeaturePickResult {
  const keyRef = useRef<string | null>(null);
  const applyPickResponse = useSessionStore((s) => s.applyPickResponse);

  return useMutation<FeaturePickResponse, Error, UseFeaturePickInput>({
    mutationFn: async ({ position }) => {
      const { sessionId, sessionVersion, gameId, activeFeatureView } =
        useSessionStore.getState();
      if (sessionId === null || sessionVersion === null || gameId === null) {
        throw new Error('Session is not initialised; cannot pick.');
      }
      if (activeFeatureView?.openedPositions.includes(position)) {
        throw new Error(`Tile at position ${position} is already opened.`);
      }
      if (keyRef.current === null) {
        keyRef.current = newIdempotencyKey();
      }
      return featurePick(keyRef.current, {
        gameId,
        sessionId,
        sessionVersion,
        position,
      });
    },
    retry: 0,
    onSuccess: (resp) => {
      applyPickResponse(resp);
      keyRef.current = null;
    },
    onError: (err) => {
      if (!(err instanceof RgsNetworkError)) {
        keyRef.current = null;
      }
    },
  });
}
