import { useEffect, useRef, useState } from 'react';

import { useSpin } from '@/game/spin/useSpin';
import { useUiStore } from '@/game/ui/uiStore';
import { useSessionStore } from '@/session/sessionStore';

import styles from './SpinButton.module.css';

const SPINNER_DELAY_MS = 250;
const STILL_WORKING_DELAY_MS = 1500;

export interface SpinButtonProps {
  /**
   * Optional callback fired after a successful spin response is applied to
   * the session store. Used by the host to trigger the Pixi animator with
   * the freshly received `SpinResponse`.
   */
  onSpinSuccess?: () => void;
}

/**
 * Player-facing Spin button. Drives one `useSpin` mutation per click.
 *
 * Disabled iff `availableActions` does not include `SPIN` (server-authoritative
 * gating, §0 Q4) or while a mutation is in flight (§2.12: one mutation per
 * player). Shows a spinner > 250 ms, "Still working…" > 1500 ms (Design Rule
 * #8). Renders disabled with a tooltip rather than hiding (§6.2).
 */
export function SpinButton({ onSpinSuccess }: SpinButtonProps = {}): JSX.Element {
  const availableActions = useSessionStore((s) => s.availableActions);
  const currentState = useSessionStore((s) => s.currentState);
  const powerBetActive = useUiStore((s) => s.powerBetActive);
  const mutation = useSpin();

  const canSpin = availableActions.includes('SPIN');
  const isPending = mutation.isPending;
  const inflightRef = useRef(false);
  const disabled = !canSpin || isPending || inflightRef.current;

  const [latency, setLatency] = useState<'idle' | 'spinner' | 'stillWorking'>('idle');

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

  const handleClick = (): void => {
    if (disabled || inflightRef.current) return;
    inflightRef.current = true;
    mutation.mutate(
      { powerBetActive },
      {
        onSuccess: () => {
          onSpinSuccess?.();
        },
        onSettled: () => {
          inflightRef.current = false;
        },
      },
    );
  };

  const tooltip = disabledTooltip(canSpin, currentState);

  return (
    <div className={styles.wrapper}>
      <button
        type="button"
        className={styles.button}
        disabled={disabled}
        aria-label="Spin"
        aria-busy={isPending}
        title={tooltip}
        onClick={handleClick}
      >
        {latency === 'spinner' || latency === 'stillWorking' ? (
          <span className={styles.spinner} aria-hidden="true" />
        ) : (
          <span className={styles.label}>SPIN</span>
        )}
      </button>
      {latency === 'stillWorking' && (
        <span className={styles.caption} role="status">
          Still working…
        </span>
      )}
    </div>
  );
}

function disabledTooltip(canSpin: boolean, state: ReturnType<typeof useSessionStore.getState>['currentState']): string | undefined {
  if (canSpin) return undefined;
  switch (state) {
    case 'FREE_SPINS_AWAITING':
      return 'Spin disabled — start your Free Spins to continue.';
    case 'PICK_COLLECT_AWAITING':
      return 'Spin disabled — start Pick & Collect to continue.';
    case 'PICK_COLLECT_LOOP':
      return 'Spin disabled — finish Pick & Collect first.';
    default:
      return 'Spin not available right now.';
  }
}
