import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';

import { devToken, type DevTokenRequest } from '@/api/dev/token';
import { useAuthStore } from '@/auth/authStore';
import { pushToast } from '@/ui/toast/toastStore';

import styles from './AuthPage.module.css';

const formSchema = z.object({
  playerId: z.string().min(1, 'Player id is required'),
  sessionId: z.string().min(1, 'Session id is required'),
  currency: z.enum(['EUR', 'USD']),
  rolesCsv: z.string().min(1, 'At least one role is required'),
  ttlMinutes: z
    .union([z.string(), z.number()])
    .transform((v) => (typeof v === 'string' ? Number(v) : v))
    .pipe(z.number().int().positive().max(24 * 60)),
});

type FormValues = z.input<typeof formSchema>;
type ParsedFormValues = z.output<typeof formSchema>;

const DEFAULTS: FormValues = {
  playerId: 'p-1001',
  sessionId: 's-2001',
  currency: 'EUR',
  rolesCsv: 'PLAYER',
  ttlMinutes: 60,
};

export function AuthPage(): JSX.Element {
  const navigate = useNavigate();
  const setToken = useAuthStore((s) => s.setToken);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: DEFAULTS,
  });

  const mutation = useMutation({
    mutationFn: (req: DevTokenRequest) => devToken(req),
    retry: 0,
  });

  const onSubmit = async (raw: FormValues): Promise<void> => {
    const parsed = formSchema.parse(raw) as ParsedFormValues;
    const roles = parsed.rolesCsv
      .split(',')
      .map((r) => r.trim().toUpperCase())
      .filter((r) => r.length > 0);
    try {
      const resp = await mutation.mutateAsync({
        playerId: parsed.playerId,
        sessionId: parsed.sessionId,
        currency: parsed.currency,
        roles,
        ttlMinutes: parsed.ttlMinutes,
      });
      setToken(resp.token);
      pushToast('info', 'Signed in. Loading game…', { ttlMs: 2000 });
      navigate('/play', { replace: true });
    } catch (err) {
      pushToast('error', err instanceof Error ? err.message : 'Failed to mint dev token');
    }
  };

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Dev sign-in</h1>
      <p className={styles.subtitle}>
        Demo profile only — mints a JWT via <code>POST /api/v1/dev/token</code>.
      </p>

      <form className={styles.form} onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className={styles.field} htmlFor="playerId">
          Player id
        </label>
        <input
          id="playerId"
          className={styles.input}
          aria-invalid={errors.playerId !== undefined}
          {...register('playerId')}
        />
        {errors.playerId && <p className={styles.error}>{errors.playerId.message}</p>}

        <label className={styles.field} htmlFor="sessionId">
          Session id
        </label>
        <input
          id="sessionId"
          className={styles.input}
          aria-invalid={errors.sessionId !== undefined}
          {...register('sessionId')}
        />
        {errors.sessionId && <p className={styles.error}>{errors.sessionId.message}</p>}

        <label className={styles.field} htmlFor="currency">
          Currency
        </label>
        <select id="currency" className={styles.input} {...register('currency')}>
          <option value="EUR">EUR</option>
          <option value="USD">USD</option>
        </select>

        <label className={styles.field} htmlFor="rolesCsv">
          Roles (comma-separated)
        </label>
        <input
          id="rolesCsv"
          className={styles.input}
          placeholder="PLAYER, ADMIN"
          aria-invalid={errors.rolesCsv !== undefined}
          {...register('rolesCsv')}
        />
        {errors.rolesCsv && <p className={styles.error}>{errors.rolesCsv.message}</p>}

        <label className={styles.field} htmlFor="ttlMinutes">
          TTL (minutes)
        </label>
        <input
          id="ttlMinutes"
          className={styles.input}
          type="number"
          min={1}
          max={24 * 60}
          aria-invalid={errors.ttlMinutes !== undefined}
          {...register('ttlMinutes')}
        />
        {errors.ttlMinutes && <p className={styles.error}>{errors.ttlMinutes.message}</p>}

        <button type="submit" className={styles.submit} disabled={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
