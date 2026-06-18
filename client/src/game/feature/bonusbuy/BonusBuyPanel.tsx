import { useState } from 'react';

import { Money, type Currency } from '@/common/money/Money';
import { BONUS_BUY_OPTIONS, type BonusBuyOption } from '@/game/math/aztec-fire';
import { useSessionStore } from '@/session/sessionStore';
import { useWalletStore } from '@/wallet/walletStore';

import { BonusBuyConfirmModal } from './BonusBuyConfirmModal';
import styles from './BonusBuyPanel.module.css';

const LABELS: Record<string, string> = {
  FREE_SPINS_BUY: 'Free Spins',
  PICK_COLLECT_BUY: 'Pick & Collect',
};

/**
 * Bonus Buy panel (Task 7.1). Lists the static `BONUS_BUY_OPTIONS` from the
 * math mirror, shows the display-only `cost = betSize × costMultiplier`, and
 * gates each option on balance availability.
 *
 * - Returns `null` when `featureFlags.bonusBuyEnabled === false` (the server
 *   never authorised this UI).
 * - The actual `cost` debited is read from the server's `feature/buy`
 *   response — this component never sends `cost` to the wire (Pitfall #9).
 */
export function BonusBuyPanel(): JSX.Element | null {
  const bonusBuyEnabled = useSessionStore((s) => s.featureFlags.bonusBuyEnabled);
  const currentState = useSessionStore((s) => s.currentState);
  const currentBet = useSessionStore((s) => s.currentBet);
  const sessionCurrency = useSessionStore((s) => s.currency);
  const balance = useWalletStore((s) => s.balance);

  const [pendingOption, setPendingOption] = useState<BonusBuyOption | null>(null);

  if (!bonusBuyEnabled) return null;

  const currency: Currency = sessionCurrency ?? 'EUR';
  const enabled = currentState === 'BASE_GAME' && currentBet !== null;
  const betPlain = currentBet?.toPlain() ?? 0;

  return (
    <section className={styles.panel} aria-label="Bonus Buy">
      <h3 className={styles.heading}>Buy Feature</h3>
      <ul className={styles.list}>
        {BONUS_BUY_OPTIONS.map((opt) => {
          const cost = Money.fromNumber(betPlain * opt.costMultiplier, currency);
          const affordable = balance !== null && balance.compareTo(cost) >= 0;
          const disabled = !enabled || !affordable;
          const reason = !enabled
            ? 'Buy unavailable during this round.'
            : !affordable
              ? 'Not enough balance'
              : undefined;

          return (
            <li key={opt.buyType} className={styles.item}>
              <div className={styles.itemHeader}>
                <span className={styles.itemName}>{LABELS[opt.buyType] ?? opt.buyType}</span>
                <span className={styles.itemMultiplier}>×{opt.costMultiplier}</span>
              </div>
              <div className={styles.itemCost}>{cost.format(currency, 'en-US')}</div>
              <button
                type="button"
                className={styles.buyButton}
                disabled={disabled}
                onClick={() => setPendingOption(opt)}
                title={reason}
                aria-label={`Buy ${LABELS[opt.buyType] ?? opt.buyType} for ${cost.format(currency, 'en-US')}`}
              >
                Buy
              </button>
            </li>
          );
        })}
      </ul>

      {pendingOption && currentBet && (
        <BonusBuyConfirmModal
          option={pendingOption}
          betSize={currentBet}
          currency={currency}
          onClose={() => setPendingOption(null)}
        />
      )}
    </section>
  );
}
