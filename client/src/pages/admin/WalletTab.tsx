import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { setBalance, type SetBalanceResponse } from '@/api/admin/setBalance';
import { useAuthStore } from '@/auth/authStore';
import { pushToast } from '@/ui/toast/toastStore';
import { useWalletStore } from '@/wallet/walletStore';

import styles from './WalletTab.module.css';

const schema = z.object({
  playerId: z.string().min(1, 'Player id is required'),
  currency: z.enum(['EUR', 'USD']),
  balance: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().nonnegative('Balance must be ≥ 0')),
});

type FormValues = z.input<typeof schema>;

export function WalletTab(): JSX.Element {
  const sessionPlayerId = useAuthStore((s) => s.playerId);
  const sessionCurrency = useAuthStore((s) => s.currency);
  const applyBalance = useWalletStore((s) => s.applyBalance);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      playerId: sessionPlayerId ?? '',
      currency: sessionCurrency ?? 'EUR',
      balance: 100,
    },
  });

  const mutation = useMutation<SetBalanceResponse, Error, z.output<typeof schema>>({
    mutationFn: (values) =>
      setBalance({
        playerId: values.playerId,
        currency: values.currency,
        balance: values.balance,
      }),
    retry: 0,
    onSuccess: (resp) => {
      pushToast('info', `Balance set to ${resp.balance} ${resp.currency}`, { ttlMs: 2500 });
      if (sessionPlayerId && resp.playerId === sessionPlayerId && resp.currency === sessionCurrency) {
        applyBalance({
          playerId: resp.playerId,
          currency: resp.currency,
          balance: resp.balance,
        });
      }
    },
    onError: (err) => pushToast('error', err.message),
  });

  const onSubmit = (raw: FormValues): void => {
    const parsed = schema.parse(raw);
    mutation.mutate(parsed);
  };

  return (
    <section className={styles.tab} aria-label="Wallet tools">
      <h2 className={styles.heading}>Set wallet balance</h2>
      <p className={styles.helper}>
        Demo profile only — overrides the player wallet via{' '}
        <code>POST /api/v1/admin/wallet/balance</code>.
      </p>

      <form className={styles.form} onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className={styles.field} htmlFor="set-balance-playerId">
          Player id
        </label>
        <input
          id="set-balance-playerId"
          className={styles.input}
          aria-invalid={errors.playerId !== undefined}
          {...register('playerId')}
        />
        {errors.playerId && <p className={styles.error}>{errors.playerId.message}</p>}

        <label className={styles.field} htmlFor="set-balance-currency">
          Currency
        </label>
        <select
          id="set-balance-currency"
          className={styles.input}
          {...register('currency')}
        >
          <option value="EUR">EUR</option>
          <option value="USD">USD</option>
        </select>

        <label className={styles.field} htmlFor="set-balance-amount">
          Balance
        </label>
        <input
          id="set-balance-amount"
          className={styles.input}
          type="number"
          step="0.01"
          min={0}
          aria-invalid={errors.balance !== undefined}
          {...register('balance')}
        />
        {errors.balance && <p className={styles.error}>{errors.balance.message}</p>}

        <button
          type="submit"
          className={styles.submit}
          disabled={isSubmitting || mutation.isPending}
        >
          {mutation.isPending ? 'Saving…' : 'Set balance'}
        </button>
      </form>

      {mutation.data && (
        <dl className={styles.result} aria-label="Set balance result">
          <dt>Player</dt>
          <dd>{mutation.data.playerId}</dd>
          <dt>Currency</dt>
          <dd>{mutation.data.currency}</dd>
          <dt>Balance</dt>
          <dd>{mutation.data.balance}</dd>
          <dt>Version</dt>
          <dd>{mutation.data.version}</dd>
          <dt>Updated at</dt>
          <dd>{mutation.data.updatedAt}</dd>
        </dl>
      )}
    </section>
  );
}
