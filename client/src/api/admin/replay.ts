import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type RoundReplayResult = components['schemas']['RoundReplayResult'];

export async function replay(roundId: string): Promise<RoundReplayResult> {
  const { data } = await http.post<RoundReplayResult>(
    `/api/v1/admin/replay/${encodeURIComponent(roundId)}`,
  );
  return data;
}
