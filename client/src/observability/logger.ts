/* eslint-disable no-console */
import { env } from '@/env';

/**
 * Single structured logger for the client. Every log line carries the active
 * `playerId`, `sessionId`, `roundId`, `gameId`, and an optional `traceId` so
 * support can join client noise to backend traces.
 *
 * Direct `console.*` calls are banned in production code (see eslint
 * `no-console`); this file is the only exemption. The logger also optionally
 * ships every line to {@link env.VITE_LOG_SINK_URL} via `sendBeacon`.
 */

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface LogContext {
  traceId?: string | undefined;
  playerId?: string | undefined;
  sessionId?: string | undefined;
  roundId?: string | undefined;
  gameId?: string | undefined;
}

export interface LogRecord extends LogContext {
  level: LogLevel;
  message: string;
  timestamp: string;
  [extra: string]: unknown;
}

type ContextProvider = () => LogContext;

const contextProviders: ContextProvider[] = [];

/**
 * Register a context provider that contributes fields to every log line.
 * Returns an unregister function.
 */
export function registerLogContext(provider: ContextProvider): () => void {
  contextProviders.push(provider);
  return () => {
    const idx = contextProviders.indexOf(provider);
    if (idx >= 0) contextProviders.splice(idx, 1);
  };
}

function collectContext(): LogContext {
  const merged: LogContext = {};
  for (const provide of contextProviders) {
    const c = provide();
    if (c.traceId !== undefined) merged.traceId = c.traceId;
    if (c.playerId !== undefined) merged.playerId = c.playerId;
    if (c.sessionId !== undefined) merged.sessionId = c.sessionId;
    if (c.roundId !== undefined) merged.roundId = c.roundId;
    if (c.gameId !== undefined) merged.gameId = c.gameId;
  }
  return merged;
}

function emitToConsole(record: LogRecord): void {
  const { level, message, ...rest } = record;
  const tag = `[${record.timestamp}] ${level.toUpperCase()} ${message}`;
  switch (level) {
    case 'error':
      console.error(tag, rest);
      return;
    case 'warn':
      console.warn(tag, rest);
      return;
    case 'info':
      console.info(tag, rest);
      return;
    case 'debug':
    default:
      console.debug(tag, rest);
  }
}

function emitToSink(record: LogRecord): void {
  const url = env.VITE_LOG_SINK_URL;
  if (!url) return;
  if (typeof navigator === 'undefined' || typeof navigator.sendBeacon !== 'function') return;
  try {
    const blob = new Blob([JSON.stringify(record)], { type: 'application/json' });
    navigator.sendBeacon(url, blob);
  } catch {
    // never throw from the logger
  }
}

function log(level: LogLevel, message: string, extra?: Record<string, unknown>): void {
  const ctx = collectContext();
  const record: LogRecord = {
    level,
    message,
    timestamp: new Date().toISOString(),
    ...ctx,
    ...(extra ?? {}),
  };
  emitToConsole(record);
  emitToSink(record);
}

export const logger = {
  debug: (message: string, extra?: Record<string, unknown>) => log('debug', message, extra),
  info: (message: string, extra?: Record<string, unknown>) => log('info', message, extra),
  warn: (message: string, extra?: Record<string, unknown>) => log('warn', message, extra),
  error: (message: string, extra?: Record<string, unknown>) => log('error', message, extra),
};

export type Logger = typeof logger;
