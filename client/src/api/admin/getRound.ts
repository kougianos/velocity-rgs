import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type RoundInspection = components['schemas']['RoundInspection'];

export async function getRound(roundId: string): Promise<RoundInspection> {
  const { data } = await http.get<RoundInspection>(
    `/api/v1/admin/round/${encodeURIComponent(roundId)}`,
  );
  return data;
}
