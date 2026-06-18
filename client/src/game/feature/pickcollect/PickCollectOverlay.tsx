import { useEffect, useRef, useState } from 'react';

import { useStartFeature } from '@/game/feature/start/useStartFeature';
import { useSessionStore } from '@/session/sessionStore';

import styles from './PickCollectOverlay.module.css';

const SPINNER_DELAY_MS = 250;
const STILL_WORKING_DELAY_MS = 1500;

/**
 * Pick & Collect HUD overlay (Task 7.7). Renders the awaiting CTA in
 * `PICK_COLLECT_AWAITING` and a compact badge in `PICK_COLLECT_LOOP`.
 * Returns `null` outside both states.
 *
 * Mirrors the structure of {@link FreeSpinsOverlay} so HUD patterns stay
 * consistent.
 */
export function PickCollectOverlay(): JSX.Element | null {
  const currentState = useSessionStore((s) => s.currentState);
  const availableActions = useSessionStore((s) => s.availableActions);
  const activeFeatureView = useSessionStore((s) => s.activeFeatureView);

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

  if (
    currentState !== 'PICK_COLLECT_AWAITING' &&
    currentState !== 'PICK_COLLECT_LOOP'
  ) {
    return null;
  }

  if (currentState === 'PICK_COLLECT_AWAITING') {
    const canStart = availableActions.includes('START_PICK_COLLECT');
    const disabled = !canStart || isPending || inflightRef.current;

    const handleStart = (): void => {
      if (disabled || inflightRef.current) return;
      inflightRef.current = true;
      mutation.mutate(
        { featureType: 'PICK_COLLECT' },
        { onSettled: () => { inflightRef.current = false; } },
      );
    };

    return (
      <div className={styles.overlay} role="region" aria-label="Pick and Collect awaiting">
        <div className={styles.card}>
          <h2 className={styles.title}>Pick &amp; Collect ready</h2>
          <p className={styles.subtitle}>Reveal tiles to collect your prize.</p>
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
              <span>Start Pick &amp; Collect</span>
            )}
          </button>
          {latency === 'stillWorking' && (
            <span className={styles.caption} role="status">Still working…</span>
          )}
        </div>
      </div>
    );
  }

  // PICK_COLLECT_LOOP — compact badge (board renders separately).
  const remaining = activeFeatureView?.remainingPicks ?? 0;
  return (
    <div className={styles.badge} role="status" aria-label="Pick and Collect active">
      <span className={styles.badgeRow}>
        <span className={styles.badgeLabel}>Picks left</span>
        <span className={styles.badgeValue}>{remaining}</span>
      </span>
    </div>
  );
}
