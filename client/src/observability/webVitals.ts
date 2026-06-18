import { onCLS, onINP, onLCP, type Metric } from 'web-vitals';

import { logger } from './logger';

/**
 * Subscribes to LCP / INP / CLS via `web-vitals` and forwards them through
 * {@link logger} on the `pagehide` event (the library batches reports and
 * flushes on visibility change, so values are final-by-pagehide).
 *
 * Safe to call multiple times; web-vitals dedupes its own listeners.
 */
export function initWebVitals(): void {
  if (typeof window === 'undefined') return;

  const report = (metric: Metric): void => {
    const level = metric.rating === 'poor' ? 'warn' : 'info';
    logger[level](`web-vitals ${metric.name}`, {
      metric: metric.name,
      value: metric.value,
      delta: metric.delta,
      rating: metric.rating,
      navigationType: metric.navigationType,
    });
  };

  // `reportAllChanges: false` keeps overhead minimal — we capture the final
  // value at page unload.
  onLCP(report);
  onINP(report);
  onCLS(report);
}
