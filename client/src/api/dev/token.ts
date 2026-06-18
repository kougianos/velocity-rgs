import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type DevTokenRequest = components['schemas']['DevTokenRequest'];
export type DevTokenResponse = components['schemas']['DevTokenResponse'];

export async function devToken(request: DevTokenRequest): Promise<DevTokenResponse> {
  const { data } = await http.post<DevTokenResponse>('/api/v1/dev/token', request);
  return data;
}
