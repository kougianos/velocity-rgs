import { describe, expect, it } from 'vitest';

import { RgsHttpError, RgsNetworkError, mapAxiosError } from './errors';

function axiosLike(status: number, data: unknown, headers: Record<string, string> = {}) {
  return {
    isAxiosError: true,
    message: `Request failed with status code ${status}`,
    response: { status, data, headers },
  };
}

describe('mapAxiosError', () => {
  it.each([
    ['VALIDATION_ERROR', 400],
    ['AUTH_FAILED', 401],
    ['FORBIDDEN_ACTION', 403],
    ['SESSION_NOT_FOUND', 404],
    ['ILLEGAL_STATE_TRANSITION', 409],
    ['SESSION_VERSION_CONFLICT', 409],
    ['IDEMPOTENCY_KEY_CONFLICT', 409],
    ['DUPLICATE_TRANSACTION', 409],
    ['INSUFFICIENT_FUNDS', 409],
    ['ORIGINAL_TRANSACTION_NOT_FOUND', 404],
    ['CURRENCY_MISMATCH', 409],
    ['BONUS_BUY_DISABLED', 409],
    ['MAX_WIN_REACHED', 409],
    ['INTERNAL_ERROR', 500],
  ] as const)('maps %s', (code, httpStatus) => {
    const result = mapAxiosError(
      axiosLike(httpStatus, {
        code,
        message: `${code} message`,
        httpStatus,
        traceId: 'c8c90d1f-24df-4cd3-95e2-33d3015d5d31',
        timestamp: '2026-06-17T10:15:30Z',
      }),
    );
    expect(result).toBeInstanceOf(RgsHttpError);
    if (result instanceof RgsHttpError) {
      expect(result.code).toBe(code);
      expect(result.httpStatus).toBe(httpStatus);
      expect(result.traceId).toBe('c8c90d1f-24df-4cd3-95e2-33d3015d5d31');
    }
  });

  it('parses field-level violation details', () => {
    const result = mapAxiosError(
      axiosLike(400, {
        code: 'VALIDATION_ERROR',
        message: 'Request validation failed',
        httpStatus: 400,
        traceId: 't-1',
        timestamp: '2026-06-17T10:15:30Z',
        details: [{ field: 'betSize', reason: 'must be > 0' }],
      }),
    );
    expect(result).toBeInstanceOf(RgsHttpError);
    if (result instanceof RgsHttpError) {
      expect(result.details).toEqual([{ field: 'betSize', reason: 'must be > 0' }]);
    }
  });

  it('falls back to INTERNAL_ERROR on a malformed body and preserves trace id header', () => {
    const result = mapAxiosError(
      axiosLike(500, { weird: 'shape' }, { 'x-trace-id': 'tid-7' }),
    );
    expect(result).toBeInstanceOf(RgsHttpError);
    if (result instanceof RgsHttpError) {
      expect(result.code).toBe('INTERNAL_ERROR');
      expect(result.httpStatus).toBe(500);
      expect(result.traceId).toBe('tid-7');
    }
  });

  it('returns RgsNetworkError on transport failure', () => {
    const result = mapAxiosError({ isAxiosError: true, message: 'Network Error' });
    expect(result).toBeInstanceOf(RgsNetworkError);
  });

  it('wraps unknown non-axios errors as RgsNetworkError', () => {
    const result = mapAxiosError(new Error('boom'));
    expect(result).toBeInstanceOf(RgsNetworkError);
  });
});
