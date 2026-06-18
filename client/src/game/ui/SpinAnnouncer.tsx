import { useEffect, useRef, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './SpinAnnouncer.module.css';

const ANNOUNCE_DEBOUNCE_MS = 400;

function formatLines(win: number, lines: number, currency: string | null): string {
  if (win <= 0) return 'No win on this spin.';
  const currencyText = currency ?? '';
  if (lines === 0) return `Win: ${win.toFixed(2)} ${currencyText}.`.trim();
  if (lines === 1) return `Win: ${win.toFixed(2)} ${currencyText} on line 1.`.trim();
  return `Win: ${win.toFixed(2)} ${currencyText} on ${lines} lines.`.trim();
}

/**
 * Visually-hidden live region that announces spin outcomes to screen readers
 * (Frontend Rule #9, Task 9.3). The Pixi canvas itself is `aria-hidden="true"`
 * so a textual surface is the only path for assistive tech.
 *
 * Announcements are debounced by {@link ANNOUNCE_DEBOUNCE_MS} so a fast
 * sequence of spins (e.g. during free spins) doesn't trample the SR buffer.
 */
export function SpinAnnouncer(): JSX.Element {
  const lastSpin = useSessionStore((s) => s.lastSpin);
  const lastPick = useSessionStore((s) => s.lastPick);
  const currency = useSessionStore((s) => s.currency);
  const [message, setMessage] = useState('');
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    if (!lastSpin) return;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    const next = formatLines(lastSpin.totalWin, lastSpin.winLines.length, currency);
    timerRef.current = window.setTimeout(() => {
      setMessage(next);
      timerRef.current = null;
    }, ANNOUNCE_DEBOUNCE_MS);
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [lastSpin, currency]);

  useEffect(() => {
    if (!lastPick) return;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    const tile = `${lastPick.resolvedTileType} ${lastPick.resolvedValue}`;
    const status = lastPick.featureCompleted
      ? `Pick & Collect complete. Total: ${lastPick.featureTotalWin ?? lastPick.currentCollected}.`
      : `Picked ${tile}. Remaining picks: ${lastPick.remainingPicks}.`;
    timerRef.current = window.setTimeout(() => {
      setMessage(status);
      timerRef.current = null;
    }, ANNOUNCE_DEBOUNCE_MS);
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [lastPick]);

  return (
    <div className={styles.live} role="status" aria-live="polite" aria-atomic="true">
      {message}
    </div>
  );
}
