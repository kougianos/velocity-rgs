import { useMemo } from 'react';

import { Money, type Currency } from '@/common/money/Money';
import { env } from '@/env';
import { useSessionStore } from '@/session/sessionStore';

import styles from './BetSelector.module.css';

function closestLadderIndex(ladder: number[], target: number): number {
  let bestIdx = 0;
  let bestDelta = Math.abs(ladder[0]! - target);
  for (let i = 1; i < ladder.length; i++) {
    const delta = Math.abs(ladder[i]! - target);
    if (delta < bestDelta) {
      bestIdx = i;
      bestDelta = delta;
    }
  }
  return bestIdx;
}

export function BetSelector(): JSX.Element {
  const ladder = env.VITE_BET_LADDER;
  const currentBet = useSessionStore((s) => s.currentBet);
  const sessionCurrency = useSessionStore((s) => s.currency);
  const currentState = useSessionStore((s) => s.currentState);
  const setCurrentBet = useSessionStore((s) => s.setCurrentBet);

  const currency: Currency = sessionCurrency ?? 'EUR';
  const disabled = currentState !== 'BASE_GAME';

  const index = useMemo(() => {
    const target = currentBet?.toPlain() ?? ladder[0]!;
    return closestLadderIndex(ladder, target);
  }, [currentBet, ladder]);

  const canDecrease = !disabled && index > 0;
  const canIncrease = !disabled && index < ladder.length - 1;

  const displayedBet = useMemo(() => {
    if (currentBet) return currentBet;
    return Money.fromNumber(ladder[index]!, currency);
  }, [currentBet, ladder, index, currency]);

  const handleStep = (delta: number): void => {
    const next = index + delta;
    if (next < 0 || next >= ladder.length) return;
    setCurrentBet(Money.fromNumber(ladder[next]!, currency));
  };

  return (
    <div
      className={styles.selector}
      role="group"
      aria-label="Bet selector"
      aria-disabled={disabled}
    >
      <button
        type="button"
        className={styles.button}
        onClick={() => handleStep(-1)}
        disabled={!canDecrease}
        aria-label="Decrease bet"
      >
        −
      </button>
      <div className={styles.amount}>
        <span className={styles.label}>Bet</span>
        <span className={styles.value}>{displayedBet.format(currency, 'en-US')}</span>
      </div>
      <button
        type="button"
        className={styles.button}
        onClick={() => handleStep(1)}
        disabled={!canIncrease}
        aria-label="Increase bet"
      >
        +
      </button>
    </div>
  );
}
