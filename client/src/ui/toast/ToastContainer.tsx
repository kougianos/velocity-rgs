import { useEffect } from 'react';

import styles from './ToastContainer.module.css';
import { useToastStore } from './toastStore';

export function ToastContainer(): JSX.Element {
  const toasts = useToastStore((s) => s.toasts);
  const dismiss = useToastStore((s) => s.dismiss);

  useEffect(() => {
    if (toasts.length === 0) return;
    const timers = toasts.map((t) =>
      window.setTimeout(() => dismiss(t.id), Math.max(0, t.ttlMs - (Date.now() - t.createdAt))),
    );
    return () => {
      timers.forEach((id) => window.clearTimeout(id));
    };
  }, [toasts, dismiss]);

  return (
    <div className={styles.container} role="region" aria-label="Notifications" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`${styles.toast} ${styles[t.level]}`} role="status">
          <span className={styles.message}>{t.message}</span>
          {t.traceId && <span className={styles.traceId}>trace: {t.traceId}</span>}
          <button
            type="button"
            aria-label="Dismiss notification"
            className={styles.dismissButton}
            onClick={() => dismiss(t.id)}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
