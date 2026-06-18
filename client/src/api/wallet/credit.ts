import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type WalletCreditRequest = components['schemas']['WalletCreditRequest'];
export type WalletCreditResponse = components['schemas']['WalletCreditResponse'];

export async function credit(
  idempotencyKey: string,
  request: WalletCreditRequest,
): Promise<WalletCreditResponse> {
  const { data } = await http.post<WalletCreditResponse>('/api/v1/wallet/credit', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return data;
}
