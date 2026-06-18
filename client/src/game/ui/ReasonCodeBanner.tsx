import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useState } from 'react';

import { useSessionStore } from '@/session/sessionStore';

import styles from './ReasonCodeBanner.module.css';

/**
 * Maps `featuresTriggered.reasonCodes` to a player-facing banner per
 * Appendix H. Unknown codes are rendered only outside production.
 */
const COPY: Record<string, { text: (delta?: number) => string; ttlMs: number; tone: 'win' | 'info' | 'cap' }> = {
  TRIGGERED_BY_SCATTER: { text: () => 'Free Spins triggered!', ttlMs: 2000, tone: 'win' },
  RETRIGGERED_FREE_SPINS: {
    text: (delta) => `+${delta ?? 0} Free Spins!`,
    ttlMs: 1500,
    tone: 'win',
  },
  ENTERED_VIA_BUY: { text: () => 'Feature purchased', ttlMs: 1500, tone: 'info' },
  MAX_WIN_CAPPED: { text: () => 'Max win reached', ttlMs: 3000, tone: 'cap' },
  PICK_COMPLETED: { text: () => 'Pick & Collect complete!', ttlMs: 2000, tone: 'win' },
};

interface BannerState {
  id: string;
  text: string;
  tone: 'win' | 'info' | 'cap';
  ttlMs: number;
}

/**
 * Top-of-stage transient overlay banner. Subscribes to the latest spin's
 * `reasonCodes` and `freeSpinsAwarded` delta, plus the latest pick's
 * `reasonCodes` (e.g. `PICK_COMPLETED`). Renders the canonical Appendix H
 * copy and auto-dismisses per the per-code TTL.
 */
export function ReasonCodeBanner(): JSX.Element {
  const spinReasonCodes = useSessionStore((s) => s.lastSpin?.featuresTriggered.reasonCodes);
  const pickReasonCodes = useSessionStore((s) => s.lastPick?.reasonCodes ?? undefined);
  const freeSpinsAwarded = useSessionStore((s) => s.lastSpin?.featuresTriggered.freeSpinsAwarded);
  const sessionVersion = useSessionStore((s) => s.sessionVersion);

  const [banner, setBanner] = useState<BannerState | null>(null);

  useEffect(() => {
    const codes = [...(spinReasonCodes ?? []), ...(pickReasonCodes ?? [])];
    if (codes.length === 0) {
      setBanner(null);
      return;
    }
    // Show the first known code; multiple codes per spin are rare but we
    // privilege the most player-visible one (win-bursts over info).
    const ordered = [
      ...codes.filter((c) => COPY[c]?.tone === 'win'),
      ...codes.filter((c) => COPY[c]?.tone === 'cap'),
      ...codes.filter((c) => COPY[c]?.tone === 'info'),
      ...codes.filter((c) => !COPY[c]),
    ];
    const code = ordered[0];
    if (!code) return;
    const entry = COPY[code];
    if (!entry) {
      if (!import.meta.env.PROD) {
        setBanner({ id: `${sessionVersion}-${code}`, text: code, tone: 'info', ttlMs: 1500 });
      }
      return;
    }
    setBanner({
      id: `${sessionVersion}-${code}`,
      text: entry.text(freeSpinsAwarded),
      tone: entry.tone,
      ttlMs: entry.ttlMs,
    });
  }, [spinReasonCodes, pickReasonCodes, freeSpinsAwarded, sessionVersion]);

  useEffect(() => {
    if (!banner) return;
    const t = window.setTimeout(() => setBanner(null), banner.ttlMs);
    return () => window.clearTimeout(t);
  }, [banner]);

  return (
    <div className={styles.host} aria-live="polite">
      <AnimatePresence>
        {banner && (
          <motion.div
            key={banner.id}
            className={`${styles.banner} ${styles[banner.tone]}`}
            initial={{ opacity: 0, y: -20, scale: 0.9 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.2 }}
            onClick={() => setBanner(null)}
            role="status"
          >
            {banner.text}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
