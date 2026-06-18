/**
 * Idempotency-Key (RFC 4122 v4 UUID) generator. See Appendix F of
 * `client/client-requirements.md`: the key is owned by the caller of a
 * mutation hook, reused on transport retry, and replaced on any definitive
 * 2xx / 4xx / 5xx HTTP response.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new Error('crypto.randomUUID is unavailable in this runtime');
  }
  return crypto.randomUUID();
}
