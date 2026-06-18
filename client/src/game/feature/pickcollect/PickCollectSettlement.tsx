import { animate, AnimatePresence, motion, useMotionValue, useTransform } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './PickCollectSettlement.module.css';

const COUNTER_DURATION_S = 1.0;
const VISIBLE_MS = 1200;

interface SettlementSnapshot {
  id: number;
  totalWin: number;
  currency: 'EUR' | 'USD';
}

/**
 * Pick & Collect settlement banner (Task 7.6). Triggered when a
 * `/feature/pick` response carries `featureCompleted === true` with a
 * `featureTotalWin`. Counter tweens the server-authoritative total over
 * {@link COUNTER_DURATION_S} per Appendix E (≤ 1200 ms total).
 */
export function PickCollectSettlement(): JSX.Element | null {
  const lastPick = useSessionStore((s) => s.lastPick);
  const sessionCurrency = useSessionStore((s) => s.currency);
  const sessionVersion = useSessionStore((s) => s.sessionVersion);

  const seenRef = useRef<number | null>(null);
  const [snapshot, setSnapshot] = useState<SettlementSnapshot | null>(null);

  useEffect(() => {
    if (!lastPick) return;
    if (!lastPick.featureCompleted) return;
    if (lastPick.featureTotalWin === null || lastPick.featureTotalWin === undefined) return;
    if (seenRef.current === lastPick.sessionVersion) return;
    seenRef.current = lastPick.sessionVersion;
    setSnapshot({
      id: lastPick.sessionVersion,
      totalWin: lastPick.featureTotalWin,
      currency: sessionCurrency ?? 'EUR',
    });
  }, [lastPick, sessionCurrency, sessionVersion]);

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
      <span className={styles.label}>Pick &amp; Collect win</span>
      <motion.span className={styles.value}>{formatted}</motion.span>
    </motion.div>
  );
}
