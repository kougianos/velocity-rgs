import axios, { type AxiosInstance } from 'axios';

import { newTraceId } from '@/common/ids/traceId';
import { env } from '@/env';

import { getAuthToken } from './authToken';
import { mapAxiosError } from './errors';

/**
 * Singleton axios instance for the RGS HTTP boundary. The instance:
 * - attaches `Authorization: Bearer <jwt>` from {@link getAuthToken} if present;
 * - attaches `X-Trace-Id` (UUID v4) on every request unless the caller has
 *   already set one;
 * - NEVER attaches `Idempotency-Key`: the caller of every mutation hook is
 *   responsible for that header (see `src/common/idempotency/key.ts` and
 *   Appendix F of `client/client-requirements.md`);
 * - converts every non-2xx response into a typed {@link RgsHttpError}, and
 *   every transport failure into a typed {@link RgsNetworkError}.
 */
export const http: AxiosInstance = axios.create({
  baseURL: env.VITE_API_BASE_URL,
  timeout: 30_000,
});

http.interceptors.request.use((config) => {
  const token = getAuthToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  if (!config.headers.has('X-Trace-Id')) {
    config.headers.set('X-Trace-Id', newTraceId());
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(mapAxiosError(error)),
);
