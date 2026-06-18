import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type SpinRequest = components['schemas']['SpinRequest'];
export type SpinResponse = components['schemas']['SpinResponse'];

export async function spin(
  idempotencyKey: string,
  request: SpinRequest,
): Promise<SpinResponse> {
  const { data } = await http.post<SpinResponse>('/api/v1/slot/spin', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return data;
}
