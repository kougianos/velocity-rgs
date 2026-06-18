import { motion } from 'framer-motion';

import { useWalletStore } from '@/wallet/walletStore';

import styles from './BalancePanel.module.css';

export function BalancePanel(): JSX.Element {
  const balance = useWalletStore((s) => s.balance);
  const currency = useWalletStore((s) => s.currency);

  if (balance === null || currency === null) {
    return (
      <div
        className={styles.panel}
        role="status"
        aria-live="polite"
        aria-label="Balance loading"
      >
        <span className={styles.label}>Balance</span>
        <span className={styles.skeleton} aria-hidden="true">
          …
        </span>
      </div>
    );
  }

  const formatted = balance.format(currency, 'en-US');

  return (
    <motion.div
      className={styles.panel}
      role="status"
      aria-live="polite"
      aria-label={`Balance ${formatted}`}
      key={formatted}
      animate={{ scale: [1, 1.08, 1] }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      <span className={styles.label}>Balance</span>
      <span className={styles.amount}>{formatted}</span>
    </motion.div>
  );
}
