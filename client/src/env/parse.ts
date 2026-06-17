import { z } from 'zod';

const REQUIRED = ['VITE_API_BASE_URL', 'VITE_DEFAULT_GAME_ID', 'VITE_DEFAULT_CURRENCY'] as const;

const booleanFromString = z
  .union([z.literal('true'), z.literal('false'), z.literal('')])
  .transform((v) => v === 'true');

const betLadderSchema = z
  .string()
  .default('0.20,0.50,1.00,2.00,5.00,10.00')
  .transform((raw, ctx) => {
    const parts = raw
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    if (parts.length === 0) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'VITE_BET_LADDER must not be empty' });
      return z.NEVER;
    }
    const nums: number[] = [];
    for (const p of parts) {
      const n = Number(p);
      if (!Number.isFinite(n) || n <= 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: `VITE_BET_LADDER entry "${p}" is not a positive number`,
        });
        return z.NEVER;
      }
      nums.push(n);
    }
    return nums;
  });

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url(),
  VITE_DEFAULT_GAME_ID: z.string().min(1),
  VITE_DEFAULT_CURRENCY: z.enum(['EUR', 'USD']),
  VITE_BET_LADDER: betLadderSchema,
  VITE_ENABLE_DEV_TOKEN: booleanFromString.default('false'),
  VITE_ENABLE_MSW: booleanFromString.default('false'),
  VITE_ENABLE_DEBUG_HUD: booleanFromString.default('false'),
  VITE_AUTH_STORAGE: z.enum(['memory', 'session']).default('memory'),
  VITE_LOG_SINK_URL: z.string().url().optional(),
  VITE_WALLET_REFRESH_MS: z
    .string()
    .default('30000')
    .transform((v, ctx) => {
      const n = Number(v);
      if (!Number.isInteger(n) || n <= 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'VITE_WALLET_REFRESH_MS must be a positive integer',
        });
        return z.NEVER;
      }
      return n;
    }),
});

export type AppEnv = z.infer<typeof envSchema>;

export function parseEnv(source: Record<string, string | undefined>): AppEnv {
  for (const key of REQUIRED) {
    const value = source[key];
    if (value === undefined || value === '') {
      throw new Error(`Missing required env var ${key}`);
    }
  }
  const result = envSchema.safeParse(source);
  if (!result.success) {
    const issue = result.error.issues[0];
    const path = issue?.path?.join('.') ?? 'env';
    const message = issue?.message ?? 'Invalid environment configuration';
    throw new Error(`Invalid env var ${path}: ${message}`);
  }
  return result.data;
}
