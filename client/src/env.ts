import { parseEnv, type AppEnv } from './env/parse';

export type { AppEnv } from './env/parse';
export { parseEnv } from './env/parse';

export const env: AppEnv = parseEnv(import.meta.env as Record<string, string | undefined>);
