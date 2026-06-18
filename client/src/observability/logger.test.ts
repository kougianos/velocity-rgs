import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { logger, registerLogContext } from './logger';

describe('logger', () => {
  const consoleSpies = {
    debug: vi.spyOn(console, 'debug').mockImplementation(() => {}),
    info: vi.spyOn(console, 'info').mockImplementation(() => {}),
    warn: vi.spyOn(console, 'warn').mockImplementation(() => {}),
    error: vi.spyOn(console, 'error').mockImplementation(() => {}),
  };

  const unregisters: (() => void)[] = [];

  beforeEach(() => {
    Object.values(consoleSpies).forEach((s) => s.mockClear());
  });

  afterEach(() => {
    while (unregisters.length > 0) unregisters.pop()!();
  });

  it('routes each level to the matching console method with the message and extras', () => {
    logger.info('hello', { foo: 'bar' });
    expect(consoleSpies.info).toHaveBeenCalledOnce();
    const [tag, payload] = consoleSpies.info.mock.calls[0]!;
    expect(tag).toMatch(/INFO hello$/);
    expect(payload).toMatchObject({ foo: 'bar' });

    logger.warn('careful');
    expect(consoleSpies.warn).toHaveBeenCalledOnce();
    logger.error('boom');
    expect(consoleSpies.error).toHaveBeenCalledOnce();
    logger.debug('deep');
    expect(consoleSpies.debug).toHaveBeenCalledOnce();
  });

  it('includes traceId, playerId, sessionId, roundId, and gameId from registered providers', () => {
    unregisters.push(
      registerLogContext(() => ({
        traceId: 'trace-1',
        playerId: 'p-1001',
        sessionId: 's-2001',
        roundId: 'r-3001',
        gameId: 'aztec-fire',
      })),
    );
    logger.info('spin');
    expect(consoleSpies.info).toHaveBeenCalledOnce();
    const [, payload] = consoleSpies.info.mock.calls[0]!;
    expect(payload).toMatchObject({
      traceId: 'trace-1',
      playerId: 'p-1001',
      sessionId: 's-2001',
      roundId: 'r-3001',
      gameId: 'aztec-fire',
    });
  });

  it('lets explicit extras override the context (per-call wins)', () => {
    unregisters.push(registerLogContext(() => ({ traceId: 'ctx' })));
    logger.warn('override', { traceId: 'explicit' });
    const [, payload] = consoleSpies.warn.mock.calls[0]!;
    expect(payload).toMatchObject({ traceId: 'explicit' });
  });

  it('does not throw when no context providers are registered', () => {
    expect(() => logger.info('orphan')).not.toThrow();
    const [, payload] = consoleSpies.info.mock.calls[0]!;
    expect(payload.traceId).toBeUndefined();
    expect(payload.playerId).toBeUndefined();
  });

  it('removes a provider when its unregister handle is called', () => {
    const off = registerLogContext(() => ({ playerId: 'transient' }));
    off();
    logger.info('after off');
    const [, payload] = consoleSpies.info.mock.calls[0]!;
    expect(payload.playerId).toBeUndefined();
  });
});
