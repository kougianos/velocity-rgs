import { describe, expect, it } from 'vitest';

import { decodeJwtPayload } from './jwt';

function encodeBase64Url(input: string): string {
  return btoa(input).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = encodeBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = encodeBase64Url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

describe('decodeJwtPayload', () => {
  it('returns the typed claims', () => {
    const token = makeJwt({
      sub: 'p-1001',
      sid: 's-2001',
      cur: 'EUR',
      exp: 1_999_999_999,
      roles: ['PLAYER'],
    });
    const claims = decodeJwtPayload(token);
    expect(claims).toEqual({
      sub: 'p-1001',
      sid: 's-2001',
      cur: 'EUR',
      exp: 1_999_999_999,
      roles: ['PLAYER'],
    });
  });

  it('defaults roles to an empty array', () => {
    const token = makeJwt({ sub: 'p', sid: 's', cur: 'USD', exp: 1_999_999_999 });
    expect(decodeJwtPayload(token).roles).toEqual([]);
  });

  it('throws on malformed token', () => {
    expect(() => decodeJwtPayload('not-a-jwt')).toThrow(/Malformed JWT/);
  });

  it('throws on missing required claim', () => {
    const token = makeJwt({ sub: 'p', sid: 's', exp: 1_999_999_999 });
    expect(() => decodeJwtPayload(token)).toThrow(/Malformed JWT claims/);
  });

  it('throws on unsupported currency', () => {
    const token = makeJwt({ sub: 'p', sid: 's', cur: 'GBP', exp: 1_999_999_999 });
    expect(() => decodeJwtPayload(token)).toThrow(/Malformed JWT claims/);
  });
});
