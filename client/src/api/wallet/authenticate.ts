import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type WalletAuthenticateRequest = components['schemas']['WalletAuthenticateRequest'];
export type WalletAuthenticateResponse = components['schemas']['WalletAuthenticateResponse'];

export async function authenticate(
  request: WalletAuthenticateRequest,
): Promise<WalletAuthenticateResponse> {
  const { data } = await http.post<WalletAuthenticateResponse>(
    '/api/v1/wallet/authenticate',
    request,
  );
  return data;
}
