import { create } from 'zustand';

import { recordTrace } from '@/observability/recentTraces';

export type ToastLevel = 'info' | 'warn' | 'error';

export interface Toast {
  id: string;
  level: ToastLevel;
  message: string;
  traceId?: string;
  ttlMs: number;
  createdAt: number;
}

interface ToastStore {
  toasts: Toast[];
  push: (toast: Omit<Toast, 'id' | 'createdAt'> & Partial<Pick<Toast, 'id' | 'createdAt'>>) => string;
  dismiss: (id: string) => void;
  clear: () => void;
}

function genId(): string {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new Error('crypto.randomUUID is unavailable in this runtime');
  }
  return crypto.randomUUID();
}

export const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  push: (input) => {
    const id = input.id ?? genId();
    const toast: Toast = {
      id,
      level: input.level,
      message: input.message,
      ...(input.traceId !== undefined ? { traceId: input.traceId } : {}),
      ttlMs: input.ttlMs,
      createdAt: input.createdAt ?? Date.now(),
    };
    set((s) => ({ toasts: [...s.toasts, toast] }));
    return id;
  },
  dismiss: (id) => {
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
  },
  clear: () => set({ toasts: [] }),
}));

export function pushToast(
  level: ToastLevel,
  message: string,
  options?: { traceId?: string; ttlMs?: number },
): string {
  if (options?.traceId) recordTrace(`toast:${level}`, options.traceId);
  return useToastStore.getState().push({
    level,
    message,
    ttlMs: options?.ttlMs ?? 4000,
    ...(options?.traceId !== undefined ? { traceId: options.traceId } : {}),
  });
}
