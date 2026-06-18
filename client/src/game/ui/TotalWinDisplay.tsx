import { animate, motion, useMotionValue, useTransform } from 'framer-motion';
import { useEffect } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './TotalWinDisplay.module.css';

const TWEEN_DURATION_S = 0.6;

/**
 * HUD-side total-win counter. Subscribes to `lastSpin.totalWin` and tweens
 * the displayed value over {@link TWEEN_DURATION_S}. The numeric value is
 * server-authoritative (Q1); this component only animates presentation.
 */
export function TotalWinDisplay(): JSX.Element | null {
  const lastSpin = useSessionStore((s) => s.lastSpin);
  const currency = useSessionStore((s) => s.currency);

  const value = useMotionValue(0);
  const formatted = useTransform(value, (v) =>
    new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency ?? 'EUR',
    }).format(v),
  );

  useEffect(() => {
    if (!lastSpin) return;
    const controls = animate(value, lastSpin.totalWin, {
      duration: TWEEN_DURATION_S,
      ease: 'easeOut',
    });
    return () => controls.stop();
  }, [lastSpin, value]);

  if (!lastSpin) return null;

  return (
    <div className={styles.panel} role="status" aria-live="polite">
      <span className={styles.label}>Win</span>
      <motion.span className={styles.value}>{formatted}</motion.span>
    </div>
  );
}
