/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_DEFAULT_GAME_ID: string;
  readonly VITE_DEFAULT_CURRENCY: 'EUR' | 'USD';
  readonly VITE_BET_LADDER?: string;
  readonly VITE_ENABLE_DEV_TOKEN?: string;
  readonly VITE_ENABLE_MSW?: string;
  readonly VITE_ENABLE_DEBUG_HUD?: string;
  readonly VITE_AUTH_STORAGE?: 'memory' | 'session';
  readonly VITE_LOG_SINK_URL?: string;
  readonly VITE_WALLET_REFRESH_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
