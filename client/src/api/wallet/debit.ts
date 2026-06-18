import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type WalletDebitRequest = components['schemas']['WalletDebitRequest'];
export type WalletDebitResponse = components['schemas']['WalletDebitResponse'];

export async function debit(
  idempotencyKey: string,
  request: WalletDebitRequest,
): Promise<WalletDebitResponse> {
  const { data } = await http.post<WalletDebitResponse>('/api/v1/wallet/debit', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return data;
}
