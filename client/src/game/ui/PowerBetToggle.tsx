import { POWER_BET } from '@/game/math/aztec-fire';
import { useUiStore } from '@/game/ui/uiStore';
import { useSessionStore } from '@/session/sessionStore';

import styles from './PowerBetToggle.module.css';

/**
 * HUD toggle for the Power Bet feature (Task 6.6). Renders `null` when the
 * server-side flag `powerBetEnabled` is false — never shown to players who
 * don't have the feature provisioned. Disabled outside `BASE_GAME` because
 * the server locks the bet/power-bet during feature loops.
 *
 * Caption shows the math-mirror multiplier for player clarity (Task 6.7).
 * The actual debit comes from the server response — see Q1.
 */
export function PowerBetToggle(): JSX.Element | null {
  const powerBetEnabled = useSessionStore((s) => s.featureFlags.powerBetEnabled);
  const currentState = useSessionStore((s) => s.currentState);
  const active = useUiStore((s) => s.powerBetActive);
  const toggle = useUiStore((s) => s.togglePowerBet);

  if (!powerBetEnabled) return null;

  const disabled = currentState !== 'BASE_GAME';
  const multiplierLabel = `${POWER_BET.betMultiplier}×`;

  return (
    <div className={styles.wrapper}>
      <button
        type="button"
        role="switch"
        aria-checked={active}
        aria-label="Power Bet"
        className={`${styles.toggle} ${active ? styles.active : ''}`}
        disabled={disabled}
        onClick={toggle}
        title={disabled ? 'Power Bet locked during this round.' : 'Toggle Power Bet'}
      >
        <span className={styles.knob} aria-hidden="true" />
        <span className={styles.label}>Power Bet</span>
        <span className={styles.multiplier}>{multiplierLabel}</span>
      </button>
      {active && !disabled && (
        <span className={styles.caption} role="status">
          Power Bet active — bet multiplier {multiplierLabel}
        </span>
      )}
    </div>
  );
}
