import styles from './LobbyPage.module.css';

export function LobbyPage(): JSX.Element {
  return (
    <main className={styles.lobby}>
      <h1 className={styles.title}>Velocity RGS — booting…</h1>
      <p className={styles.subtitle}>Slot client bootstrap (M0)</p>
    </main>
  );
}
