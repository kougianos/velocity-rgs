import { useAuthStore } from '@/auth/authStore';
import { BonusBuyPanel } from '@/game/feature/bonusbuy/BonusBuyPanel';
import { FreeSpinsOverlay } from '@/game/feature/freespins/FreeSpinsOverlay';
import { FreeSpinsSettlement } from '@/game/feature/freespins/FreeSpinsSettlement';
import { PickBoardScene } from '@/game/feature/pickcollect/PickBoardScene';
import { PickCollectOverlay } from '@/game/feature/pickcollect/PickCollectOverlay';
import { PickCollectSettlement } from '@/game/feature/pickcollect/PickCollectSettlement';
import { SlotStage } from '@/game/pixi/SlotStage';
import { PowerBetToggle } from '@/game/ui/PowerBetToggle';
import { ReasonCodeBanner } from '@/game/ui/ReasonCodeBanner';
import { SpinAnnouncer } from '@/game/ui/SpinAnnouncer';
import { SpinButton } from '@/game/ui/SpinButton';
import { TotalWinDisplay } from '@/game/ui/TotalWinDisplay';
import { useSessionStore } from '@/session/sessionStore';
import { useSessionInit } from '@/session/useSessionInit';
import { BalancePanel } from '@/wallet/components/BalancePanel';
import { BetSelector } from '@/wallet/components/BetSelector';
import { useWalletBalance } from '@/wallet/useWalletBalance';

import styles from './PlayPage.module.css';

/**
 * M3 + M4: shows the balance pill / bet selector on top of the FSM mirror
 * placeholder and mounts the Pixi slot stage (placeholder matrix until the
 * first `/spin` lands in M5). M7: surfaces Bonus Buy & Pick & Collect.
 */
export function PlayPage(): JSX.Element {
  const initQuery = useSessionInit();
  useWalletBalance();
  const playerId = useAuthStore((s) => s.playerId);

  const sessionVersion = useSessionStore((s) => s.sessionVersion);
  const gameId = useSessionStore((s) => s.gameId);
  const currency = useSessionStore((s) => s.currency);
  const currentState = useSessionStore((s) => s.currentState);
  const remainingFreeSpins = useSessionStore((s) => s.remainingFreeSpins);
  const accumulatedFreeSpinsWin = useSessionStore((s) => s.accumulatedFreeSpinsWin);
  const currentBet = useSessionStore((s) => s.currentBet);
  const availableActions = useSessionStore((s) => s.availableActions);
  const featureFlags = useSessionStore((s) => s.featureFlags);
  const activeFeatureView = useSessionStore((s) => s.activeFeatureView);

  const resuming = currentState !== null && currentState !== 'BASE_GAME' && initQuery.isSuccess;

  return (
    <main className={styles.play}>
      <header className={styles.header}>
        <div className={styles.headerRow}>
          <div>
            <h1 className={styles.title}>Velocity RGS — {gameId ?? '…'}</h1>
            <p className={styles.subtitle}>Player {playerId ?? '…'}</p>
          </div>
          <BalancePanel />
        </div>
      </header>

      {initQuery.isLoading && <p className={styles.status}>Loading session…</p>}
      {initQuery.isError && (
        <p className={styles.error} role="alert">
          Failed to load session: {initQuery.error.message}
        </p>
      )}

      {resuming && (
        <div className={styles.resumeBanner} role="status">
          Resuming your previous round…
        </div>
      )}

      <section className={styles.hud} aria-label="Game HUD">
        <BetSelector />
        <SpinButton />
        <PowerBetToggle />
        <TotalWinDisplay />
        <BonusBuyPanel />
      </section>

      <div className={styles.stageWrap}>
        <ReasonCodeBanner />
        <FreeSpinsSettlement />
        <PickCollectSettlement />
        <SlotStage />
        <FreeSpinsOverlay />
        <PickCollectOverlay />
        <SpinAnnouncer />
      </div>

      <PickBoardScene />

      <section className={styles.stateBlock} aria-label="Session state">
        <dl className={styles.stateGrid}>
          <dt>Current state</dt>
          <dd>{currentState ?? '—'}</dd>

          <dt>Session version</dt>
          <dd>{sessionVersion ?? '—'}</dd>

          <dt>Currency</dt>
          <dd>{currency ?? '—'}</dd>

          <dt>Current bet</dt>
          <dd>{currentBet?.toString() ?? '—'}</dd>

          <dt>Remaining free spins</dt>
          <dd>{remainingFreeSpins}</dd>

          <dt>Accumulated FS win</dt>
          <dd>{accumulatedFreeSpinsWin?.toString() ?? '—'}</dd>

          <dt>Available actions</dt>
          <dd>{availableActions.length === 0 ? '—' : availableActions.join(', ')}</dd>

          <dt>Feature flags</dt>
          <dd>{JSON.stringify(featureFlags)}</dd>
        </dl>
      </section>

      <details className={styles.details}>
        <summary>Active feature view (raw)</summary>
        <pre className={styles.json}>{JSON.stringify(activeFeatureView, null, 2)}</pre>
      </details>
    </main>
  );
}
