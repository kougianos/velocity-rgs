import { describe, expect, it } from 'vitest';

import { parseEnv } from './parse';

const baseValid = {
  VITE_API_BASE_URL: 'http://localhost:8080',
  VITE_DEFAULT_GAME_ID: 'aztec-fire',
  VITE_DEFAULT_CURRENCY: 'EUR',
};

describe('parseEnv', () => {
  it('throws when a required var is missing', () => {
    expect(() => parseEnv({})).toThrow('Missing required env var VITE_API_BASE_URL');
  });

  it('throws with the specific missing var name', () => {
    const partial = { ...baseValid, VITE_DEFAULT_CURRENCY: undefined };
    expect(() => parseEnv(partial)).toThrow('Missing required env var VITE_DEFAULT_CURRENCY');
  });

  it('parses required values and applies defaults', () => {
    const result = parseEnv(baseValid);
    expect(result.VITE_API_BASE_URL).toBe('http://localhost:8080');
    expect(result.VITE_DEFAULT_GAME_ID).toBe('aztec-fire');
    expect(result.VITE_DEFAULT_CURRENCY).toBe('EUR');
    expect(result.VITE_ENABLE_DEV_TOKEN).toBe(false);
    expect(result.VITE_ENABLE_MSW).toBe(false);
    expect(result.VITE_AUTH_STORAGE).toBe('memory');
    expect(result.VITE_WALLET_REFRESH_MS).toBe(30000);
    expect(result.VITE_BET_LADDER).toEqual([0.2, 0.5, 1.0, 2.0, 5.0, 10.0]);
  });

  it('parses custom bet ladder', () => {
    const result = parseEnv({ ...baseValid, VITE_BET_LADDER: '1,2,3' });
    expect(result.VITE_BET_LADDER).toEqual([1, 2, 3]);
  });

  it('rejects non-positive bet entries', () => {
    expect(() => parseEnv({ ...baseValid, VITE_BET_LADDER: '1,-2' })).toThrow(
      /VITE_BET_LADDER entry "-2" is not a positive number/,
    );
  });

  it('rejects unsupported currency', () => {
    expect(() => parseEnv({ ...baseValid, VITE_DEFAULT_CURRENCY: 'GBP' })).toThrow();
  });

  it('rejects invalid URL', () => {
    expect(() => parseEnv({ ...baseValid, VITE_API_BASE_URL: 'not-a-url' })).toThrow();
  });

  it('parses boolean-like flags', () => {
    const result = parseEnv({
      ...baseValid,
      VITE_ENABLE_DEV_TOKEN: 'true',
      VITE_ENABLE_MSW: 'true',
    });
    expect(result.VITE_ENABLE_DEV_TOKEN).toBe(true);
    expect(result.VITE_ENABLE_MSW).toBe(true);
  });
});
