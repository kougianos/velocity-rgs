import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type WalletBalanceResponse = components['schemas']['WalletBalanceResponse'];

export async function balance(): Promise<WalletBalanceResponse> {
  const { data } = await http.get<WalletBalanceResponse>('/api/v1/wallet/balance');
  return data;
}
