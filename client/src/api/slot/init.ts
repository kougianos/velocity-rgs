import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type SlotInitRequest = components['schemas']['SlotInitRequest'];
export type SlotInitResponse = components['schemas']['SlotInitResponse'];

export async function init(request: SlotInitRequest): Promise<SlotInitResponse> {
  const { data } = await http.post<SlotInitResponse>('/api/v1/slot/init', request);
  return data;
}
