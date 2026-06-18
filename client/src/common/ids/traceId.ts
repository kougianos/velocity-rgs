/**
 * UUID v4 helpers for HTTP correlation. Trace ids are generated per request by
 * the axios request interceptor and echoed back by the server on every response.
 */
export function newTraceId(): string {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new Error('crypto.randomUUID is unavailable in this runtime');
  }
  return crypto.randomUUID();
}
