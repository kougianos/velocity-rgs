import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type SetBalanceRequest = components['schemas']['SetBalanceRequest'];
export type SetBalanceResponse = components['schemas']['SetBalanceResponse'];

export async function setBalance(request: SetBalanceRequest): Promise<SetBalanceResponse> {
  const { data } = await http.post<SetBalanceResponse>('/api/v1/admin/wallet/balance', request);
  return data;
}
