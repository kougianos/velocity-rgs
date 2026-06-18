import styles from './AdminPage.module.css';

export function AdminPage(): JSX.Element {
  return (
    <main className={styles.admin}>
      <h1 className={styles.title}>Admin tools</h1>
      <p className={styles.subtitle}>Coming in M8 — set-balance, session/round inspectors, replay, simulator.</p>
    </main>
  );
}
