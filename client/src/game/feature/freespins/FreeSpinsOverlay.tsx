import { useEffect, useRef, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './FreeSpinsOverlay.module.css';
import { useStartFeature } from './useStartFeature';

const SPINNER_DELAY_MS = 250;
const STILL_WORKING_DELAY_MS = 1500;

/**
 * Free Spins HUD overlay. Renders the awaiting CTA or the in-loop badge
 * depending on `currentState` (Task 6.1). Returns `null` outside the Free
 * Spins states so it never crowds base-game UI.
 *
 * The Start CTA gates strictly on `availableActions` per §0 Q4 — the
 * server's view of what's allowed wins.
 */
export function FreeSpinsOverlay(): JSX.Element | null {
  const currentState = useSessionStore((s) => s.currentState);
  const availableActions = useSessionStore((s) => s.availableActions);
  const remainingFreeSpins = useSessionStore((s) => s.remainingFreeSpins);
  const accumulated = useSessionStore((s) => s.accumulatedFreeSpinsWin);
  const currency = useSessionStore((s) => s.currency);

  const mutation = useStartFeature();
  const inflightRef = useRef(false);
  const [latency, setLatency] = useState<'idle' | 'spinner' | 'stillWorking'>('idle');

  const isPending = mutation.isPending;

  useEffect(() => {
    if (!isPending) {
      setLatency('idle');
      return;
    }
    const t1 = window.setTimeout(() => setLatency('spinner'), SPINNER_DELAY_MS);
    const t2 = window.setTimeout(() => setLatency('stillWorking'), STILL_WORKING_DELAY_MS);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
  }, [isPending]);

  if (currentState !== 'FREE_SPINS_AWAITING' && currentState !== 'FREE_SPINS_LOOP') {
    return null;
  }

  if (currentState === 'FREE_SPINS_AWAITING') {
    const canStart = availableActions.includes('START_FREE_SPINS');
    const disabled = !canStart || isPending || inflightRef.current;

    const handleStart = (): void => {
      if (disabled || inflightRef.current) return;
      inflightRef.current = true;
      mutation.mutate(
        { featureType: 'FREE_SPINS' },
        { onSettled: () => { inflightRef.current = false; } },
      );
    };

    return (
      <div className={styles.overlay} role="region" aria-label="Free Spins awaiting">
        <div className={styles.card}>
          <h2 className={styles.title}>Free Spins ready</h2>
          <p className={styles.subtitle}>
            {remainingFreeSpins} free {remainingFreeSpins === 1 ? 'spin' : 'spins'} awarded.
          </p>
          <button
            type="button"
            className={styles.cta}
            disabled={disabled}
            aria-busy={isPending}
            onClick={handleStart}
            title={canStart ? undefined : 'Start not available right now.'}
          >
            {latency === 'spinner' || latency === 'stillWorking' ? (
              <span className={styles.spinner} aria-hidden="true" />
            ) : (
              <span>Start Free Spins</span>
            )}
          </button>
          {latency === 'stillWorking' && (
            <span className={styles.caption} role="status">Still working…</span>
          )}
        </div>
      </div>
    );
  }

  // FREE_SPINS_LOOP — compact badge
  return (
    <div className={styles.badge} role="status" aria-label="Free Spins active">
      <span className={styles.badgeRow}>
        <span className={styles.badgeLabel}>Free Spins</span>
        <span className={styles.badgeValue}>{remainingFreeSpins}</span>
      </span>
      <span className={styles.badgeRow}>
        <span className={styles.badgeLabel}>Accumulated</span>
        <span className={styles.badgeValue}>
          {accumulated ? accumulated.format(currency ?? 'EUR', 'en-US') : '—'}
        </span>
      </span>
    </div>
  );
}
