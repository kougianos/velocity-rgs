import { animate, AnimatePresence, motion, useMotionValue, useTransform } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './FreeSpinsSettlement.module.css';

const COUNTER_DURATION_S = 0.8;
const VISIBLE_MS = 1200;

interface SettlementSnapshot {
  id: number;
  totalWin: number;
  currency: 'EUR' | 'USD';
}

/**
 * Renders the Free Spins settlement banner when the FSM transitions from
 * `FREE_SPINS_LOOP` to `BASE_GAME` (Task 6.5). Counter tweens the accumulated
 * win over {@link COUNTER_DURATION_S} per Appendix E (1200 ms total). The
 * displayed value is server-authoritative — this component does no math.
 */
export function FreeSpinsSettlement(): JSX.Element | null {
  const currentState = useSessionStore((s) => s.currentState);
  const lastSpin = useSessionStore((s) => s.lastSpin);
  const sessionCurrency = useSessionStore((s) => s.currency);

  const prevStateRef = useRef(currentState);
  const [snapshot, setSnapshot] = useState<SettlementSnapshot | null>(null);

  useEffect(() => {
    const prev = prevStateRef.current;
    prevStateRef.current = currentState;
    if (prev !== 'FREE_SPINS_LOOP' || currentState !== 'BASE_GAME') return;
    if (!lastSpin) return;
    const totalWin = lastSpin.sessionState.accumulatedFreeSpinsWin;
    if (totalWin <= 0) return;
    setSnapshot({
      id: lastSpin.sessionVersion,
      totalWin,
      currency: sessionCurrency ?? 'EUR',
    });
  }, [currentState, lastSpin, sessionCurrency]);

  useEffect(() => {
    if (!snapshot) return;
    const t = window.setTimeout(() => setSnapshot(null), VISIBLE_MS);
    return () => window.clearTimeout(t);
  }, [snapshot]);

  return (
    <AnimatePresence>
      {snapshot && <SettlementCard key={snapshot.id} snapshot={snapshot} />}
    </AnimatePresence>
  );
}

function SettlementCard({ snapshot }: { snapshot: SettlementSnapshot }): JSX.Element {
  const value = useMotionValue(0);
  const formatted = useTransform(value, (v) =>
    new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: snapshot.currency,
    }).format(v),
  );

  useEffect(() => {
    const controls = animate(value, snapshot.totalWin, {
      duration: COUNTER_DURATION_S,
      ease: 'easeOut',
    });
    return () => controls.stop();
  }, [snapshot.totalWin, value]);

  return (
    <motion.div
      className={styles.card}
      initial={{ opacity: 0, y: -10, scale: 0.95 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: 0.2 }}
      role="status"
      aria-live="polite"
    >
      <span className={styles.label}>Free Spins win</span>
      <motion.span className={styles.value}>{formatted}</motion.span>
    </motion.div>
  );
}
