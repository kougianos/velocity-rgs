import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type FeatureBuyRequest = components['schemas']['FeatureBuyRequest'];
export type FeatureBuyResponse = components['schemas']['FeatureBuyResponse'];

export async function featureBuy(
  idempotencyKey: string,
  request: FeatureBuyRequest,
): Promise<FeatureBuyResponse> {
  const { data } = await http.post<FeatureBuyResponse>(
    '/api/v1/slot/feature/buy',
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
  return data;
}
