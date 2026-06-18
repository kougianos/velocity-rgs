import { useEffect, useRef, useState } from 'react';

import type { Currency, Money } from '@/common/money/Money';
import type { BonusBuyOption } from '@/game/math/aztec-fire';

import styles from './BonusBuyConfirmModal.module.css';
import { useBuyFeature } from './useBuyFeature';

const SPINNER_DELAY_MS = 250;
const STILL_WORKING_DELAY_MS = 1500;

const LABELS: Record<string, string> = {
  FREE_SPINS_BUY: 'Free Spins',
  PICK_COLLECT_BUY: 'Pick & Collect',
};

export interface BonusBuyConfirmModalProps {
  option: BonusBuyOption;
  betSize: Money;
  currency: Currency;
  onClose: () => void;
}

/**
 * Confirmation modal for a Bonus Buy (Task 7.2). The cost shown here is the
 * display-only product `betSize × costMultiplier`; the server's response is
 * the authoritative debit value.
 */
export function BonusBuyConfirmModal({
  option,
  betSize,
  currency,
  onClose,
}: BonusBuyConfirmModalProps): JSX.Element {
  const mutation = useBuyFeature();
  const inflightRef = useRef(false);
  const [latency, setLatency] = useState<'idle' | 'spinner' | 'stillWorking'>('idle');

  const cost = betSize.multiply(option.costMultiplier);
  const isPending = mutation.isPending;

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

  const handleConfirm = (): void => {
    if (isPending || inflightRef.current) return;
    inflightRef.current = true;
    mutation.mutate(
      { buyType: option.buyType, betSize: betSize.toPlain() },
      {
        onSuccess: () => {
          inflightRef.current = false;
          onClose();
        },
        onError: () => {
          inflightRef.current = false;
        },
      },
    );
  };

  const label = LABELS[option.buyType] ?? option.buyType;

  return (
    <div className={styles.backdrop} role="dialog" aria-modal="true" aria-label={`Confirm buy ${label}`}>
      <div className={styles.modal}>
        <h2 className={styles.title}>Buy {label}?</h2>
        <p className={styles.body}>
          You will be charged <strong>{cost.format(currency, 'en-US')}</strong>.
        </p>
        <p className={styles.fineprint}>
          Final cost is determined by the server. Bet size: {betSize.format(currency, 'en-US')}.
        </p>
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.secondary}
            onClick={onClose}
            disabled={isPending}
          >
            Cancel
          </button>
          <button
            type="button"
            className={styles.primary}
            onClick={handleConfirm}
            disabled={isPending}
            aria-busy={isPending}
          >
            {latency === 'spinner' || latency === 'stillWorking' ? (
              <span className={styles.spinner} aria-hidden="true" />
            ) : (
              <span>Confirm</span>
            )}
          </button>
        </div>
        {latency === 'stillWorking' && (
          <span className={styles.caption} role="status">Still working…</span>
        )}
      </div>
    </div>
  );
}
