import { describe, expect, it } from 'vitest';

import { newIdempotencyKey } from './key';

describe('newIdempotencyKey', () => {
  it('returns an RFC 4122 v4 UUID', () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });

  it('is unique across calls', () => {
    const keys = new Set<string>();
    for (let i = 0; i < 50; i += 1) keys.add(newIdempotencyKey());
    expect(keys.size).toBe(50);
  });
});
