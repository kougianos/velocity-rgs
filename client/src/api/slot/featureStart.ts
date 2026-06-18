import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type FeatureStartRequest = components['schemas']['FeatureStartRequest'];
export type FeatureStartResponse = components['schemas']['FeatureStartResponse'];

export async function featureStart(
  idempotencyKey: string,
  request: FeatureStartRequest,
): Promise<FeatureStartResponse> {
  const { data } = await http.post<FeatureStartResponse>(
    '/api/v1/slot/feature/start',
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
  return data;
}
