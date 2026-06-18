import { describe, expect, it } from 'vitest';

import { newTraceId } from './traceId';

describe('newTraceId', () => {
  it('returns a UUID-shaped string', () => {
    expect(newTraceId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
  });
});
