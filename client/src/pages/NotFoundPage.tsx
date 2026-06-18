import { Link } from 'react-router-dom';

import styles from './NotFoundPage.module.css';

export function NotFoundPage(): JSX.Element {
  return (
    <main className={styles.notFound}>
      <h1 className={styles.title}>404 — page not found</h1>
      <p>
        <Link to="/" className={styles.link}>
          Return to the lobby
        </Link>
      </p>
    </main>
  );
}
