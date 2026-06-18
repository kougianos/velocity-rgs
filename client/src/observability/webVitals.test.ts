import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { logger } from './logger';
import { initWebVitals } from './webVitals';

const onLCP = vi.fn();
const onINP = vi.fn();
const onCLS = vi.fn();

vi.mock('web-vitals', () => ({
  onLCP: (cb: (m: unknown) => void) => onLCP(cb),
  onINP: (cb: (m: unknown) => void) => onINP(cb),
  onCLS: (cb: (m: unknown) => void) => onCLS(cb),
}));

describe('initWebVitals', () => {
  beforeEach(() => {
    onLCP.mockReset();
    onINP.mockReset();
    onCLS.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('subscribes to LCP, INP, and CLS', () => {
    initWebVitals();
    expect(onLCP).toHaveBeenCalledOnce();
    expect(onINP).toHaveBeenCalledOnce();
    expect(onCLS).toHaveBeenCalledOnce();
  });

  it('reports a "good" metric at info level', () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    initWebVitals();
    const [cb] = onLCP.mock.calls[0]!;
    (cb as (m: unknown) => void)({
      name: 'LCP',
      value: 1234,
      delta: 12,
      rating: 'good',
      navigationType: 'navigate',
    });
    expect(infoSpy).toHaveBeenCalled();
    const [tag, payload] = infoSpy.mock.calls.at(-1)!;
    expect(tag).toMatch(/web-vitals LCP/);
    expect(payload).toMatchObject({ metric: 'LCP', rating: 'good' });
  });

  it('reports a "poor" metric at warn level', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    initWebVitals();
    const [cb] = onCLS.mock.calls[0]!;
    (cb as (m: unknown) => void)({
      name: 'CLS',
      value: 0.5,
      delta: 0.1,
      rating: 'poor',
      navigationType: 'navigate',
    });
    expect(warnSpy).toHaveBeenCalled();
    const [, payload] = warnSpy.mock.calls.at(-1)!;
    expect(payload).toMatchObject({ metric: 'CLS', rating: 'poor' });
  });

  it('is a no-op when window is undefined', () => {
    const originalWindow = globalThis.window;
    // @ts-expect-error simulate non-browser
    delete globalThis.window;
    expect(() => initWebVitals()).not.toThrow();
    expect(onLCP).not.toHaveBeenCalled();
    globalThis.window = originalWindow;
  });

  it('does not throw when the logger is invoked via web-vitals', () => {
    initWebVitals();
    const [cb] = onINP.mock.calls[0]!;
    expect(() =>
      (cb as (m: unknown) => void)({
        name: 'INP',
        value: 200,
        delta: 200,
        rating: 'needs-improvement',
        navigationType: 'navigate',
      }),
    ).not.toThrow();
    // also use the imported logger to keep the dependency referenced
    expect(typeof logger.info).toBe('function');
  });
});
