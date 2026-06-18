import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';

import { getSession, type SessionInspection } from '@/api/admin/getSession';
import { pushToast } from '@/ui/toast/toastStore';

import styles from './InspectorTab.module.css';
import { JsonView } from './JsonView';

export function SessionTab(): JSX.Element {
  const [playerId, setPlayerId] = useState('p-1001');

  const mutation = useMutation<SessionInspection, Error, string>({
    mutationFn: (id) => getSession(id),
    retry: 0,
    onError: (err) => pushToast('error', err.message),
  });

  const handleSubmit = (e: React.FormEvent): void => {
    e.preventDefault();
    const trimmed = playerId.trim();
    if (trimmed.length === 0) {
      pushToast('warn', 'Player id is required');
      return;
    }
    mutation.mutate(trimmed);
  };

  return (
    <section className={styles.tab} aria-label="Session inspector">
      <h2 className={styles.heading}>Session inspector</h2>
      <p className={styles.helper}>
        Fetches Redis + DB snapshot via <code>GET /api/v1/admin/session/{'{playerId}'}</code>.
      </p>

      <form className={styles.form} onSubmit={handleSubmit}>
        <label className={styles.field} htmlFor="session-playerId">
          Player id
        </label>
        <input
          id="session-playerId"
          className={styles.input}
          value={playerId}
          onChange={(e) => setPlayerId(e.target.value)}
          aria-invalid={playerId.trim().length === 0}
        />
        <button
          type="submit"
          className={styles.submit}
          disabled={mutation.isPending}
          aria-busy={mutation.isPending}
        >
          {mutation.isPending ? 'Loading…' : 'Inspect'}
        </button>
      </form>

      {mutation.isError && (
        <p className={styles.error} role="alert">
          {mutation.error.message}
        </p>
      )}

      {mutation.data && (
        <div className={styles.result}>
          <header className={styles.resultHeader}>
            <span>
              Session <strong>{mutation.data.sessionId}</strong> for player{' '}
              <strong>{mutation.data.playerId}</strong>
            </span>
            <span
              className={mutation.data.cachedInRedis ? styles.badgeOk : styles.badgeWarn}
              aria-label={
                mutation.data.cachedInRedis ? 'Cached in Redis' : 'Not cached in Redis'
              }
            >
              {mutation.data.cachedInRedis ? 'REDIS' : 'DB-ONLY'}
            </span>
          </header>
          <JsonView data={mutation.data} label="Session inspection JSON" />
        </div>
      )}
    </section>
  );
}
