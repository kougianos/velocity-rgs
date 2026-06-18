import { useEffect, useState } from 'react';

import { useAuthStore } from '@/auth/authStore';
import { env } from '@/env';
import { useSessionStore } from '@/session/sessionStore';
import { useWalletStore } from '@/wallet/walletStore';

import styles from './DebugHud.module.css';
import {
  getRecentTraces,
  subscribeRecentTraces,
  type RecentTrace,
} from './recentTraces';

function useRecentTraces(): readonly RecentTrace[] {
  const [snap, setSnap] = useState<readonly RecentTrace[]>(() => getRecentTraces());
  useEffect(() => subscribeRecentTraces(setSnap), []);
  return snap;
}

/**
 * Collapsible operator-only HUD gated by `VITE_ENABLE_DEBUG_HUD`. Surfaces
 * the live `playerId`, `sessionId`, `gameId`, `sessionVersion`, `balance`,
 * and the last few `traceId`s reported by error toasts and the spin loop.
 */
export function DebugHud(): JSX.Element | null {
  const [open, setOpen] = useState(false);
  const playerId = useAuthStore((s) => s.playerId);
  const sessionId = useSessionStore((s) => s.sessionId);
  const gameId = useSessionStore((s) => s.gameId);
  const sessionVersion = useSessionStore((s) => s.sessionVersion);
  const currentState = useSessionStore((s) => s.currentState);
  const balance = useWalletStore((s) => s.balance);
  const currency = useWalletStore((s) => s.currency);
  const recent = useRecentTraces();

  if (!env.VITE_ENABLE_DEBUG_HUD) return null;

  return (
    <aside className={styles.hud} aria-label="Debug HUD">
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls="debug-hud-body"
        onClick={() => setOpen((v) => !v)}
      >
        {open ? '▼' : '▲'} Debug
      </button>
      {open && (
        <div id="debug-hud-body" className={styles.body}>
          <dl className={styles.grid}>
            <dt>playerId</dt>
            <dd>{playerId ?? '—'}</dd>
            <dt>sessionId</dt>
            <dd>{sessionId ?? '—'}</dd>
            <dt>gameId</dt>
            <dd>{gameId ?? '—'}</dd>
            <dt>sessionVersion</dt>
            <dd>{sessionVersion ?? '—'}</dd>
            <dt>currentState</dt>
            <dd>{currentState ?? '—'}</dd>
            <dt>balance</dt>
            <dd>{balance && currency ? balance.format(currency) : '—'}</dd>
          </dl>
          <h2 className={styles.tracesHeading}>Recent traces</h2>
          {recent.length === 0 ? (
            <p className={styles.empty}>No traces recorded yet.</p>
          ) : (
            <ul className={styles.traces}>
              {recent.map((t) => (
                <li key={t.id}>
                  <span className={styles.tAt}>{t.at}</span>
                  <span className={styles.tSource}>{t.source}</span>
                  <code className={styles.tId}>{t.traceId}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </aside>
  );
}
