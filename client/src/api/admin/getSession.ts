import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type SessionInspection = components['schemas']['SessionInspection'];

export async function getSession(playerId: string): Promise<SessionInspection> {
  const { data } = await http.get<SessionInspection>(
    `/api/v1/admin/session/${encodeURIComponent(playerId)}`,
  );
  return data;
}
