import { useMutation, type UseMutationResult } from '@tanstack/react-query';
import { useRef } from 'react';

import type { FeatureType } from '@/api/enums';
import { RgsNetworkError } from '@/api/http/errors';
import { featureStart, type FeatureStartResponse } from '@/api/slot/featureStart';
import { newIdempotencyKey } from '@/common/idempotency/key';
import { useSessionStore } from '@/session/sessionStore';

export interface UseStartFeatureInput {
  featureType: FeatureType;
}

export type UseStartFeatureResult = UseMutationResult<
  FeatureStartResponse,
  Error,
  UseStartFeatureInput
>;

/**
 * Mutation hook wrapping `POST /api/v1/slot/feature/start`. Mirrors the
 * idempotency-key lifecycle from Appendix F: one key per user intent,
 * reused on transport failure, cleared on any definitive HTTP response.
 *
 * The caller picks the {@link FeatureType}. Gating against
 * `availableActions` is the caller's responsibility (Q4).
 */
export function useStartFeature(): UseStartFeatureResult {
  const keyRef = useRef<string | null>(null);
  const applyFeatureStartResponse = useSessionStore((s) => s.applyFeatureStartResponse);

  return useMutation<FeatureStartResponse, Error, UseStartFeatureInput>({
    mutationFn: async ({ featureType }) => {
      const { sessionId, sessionVersion, gameId } = useSessionStore.getState();
      if (sessionId === null || sessionVersion === null || gameId === null) {
        throw new Error('Session is not initialised; cannot start a feature.');
      }
      if (keyRef.current === null) {
        keyRef.current = newIdempotencyKey();
      }
      return featureStart(keyRef.current, {
        gameId,
        sessionId,
        sessionVersion,
        featureType,
      });
    },
    retry: 0,
    onSuccess: (resp) => {
      applyFeatureStartResponse(resp);
      keyRef.current = null;
    },
    onError: (err) => {
      if (!(err instanceof RgsNetworkError)) {
        keyRef.current = null;
      }
    },
  });
}
