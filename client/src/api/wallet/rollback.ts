import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type WalletRollbackRequest = components['schemas']['WalletRollbackRequest'];
export type WalletRollbackResponse = components['schemas']['WalletRollbackResponse'];

export async function rollback(
  idempotencyKey: string,
  request: WalletRollbackRequest,
): Promise<WalletRollbackResponse> {
  const { data } = await http.post<WalletRollbackResponse>('/api/v1/wallet/rollback', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return data;
}
