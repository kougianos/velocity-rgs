import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type FeaturePickRequest = components['schemas']['FeaturePickRequest'];
export type FeaturePickResponse = components['schemas']['FeaturePickResponse'];

export async function featurePick(
  idempotencyKey: string,
  request: FeaturePickRequest,
): Promise<FeaturePickResponse> {
  const { data } = await http.post<FeaturePickResponse>(
    '/api/v1/slot/feature/pick',
    request,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
  return data;
}
