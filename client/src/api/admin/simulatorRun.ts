import type { components } from '@/api/generated/openapi';
import { http } from '@/api/http/axios';

export type RtpSimulationRequest = components['schemas']['RtpSimulationRequest'];
export type RtpReport = components['schemas']['RtpReport'];

export async function simulatorRun(request: RtpSimulationRequest): Promise<RtpReport> {
  const { data } = await http.post<RtpReport>('/api/v1/admin/simulator/run', request);
  return data;
}
