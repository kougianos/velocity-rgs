import { z } from 'zod';

import type { components } from '@/api/generated/openapi';

/**
 * Canonical ApiError envelope per Appendix A.8 of `client-requirements.md`
 * and `com.velocity.rgs.common.error.ApiError`. Parsed at the axios response
 * interceptor; never accepted as `unknown`.
 */
const errorCodeSchema = z.enum([
  'VALIDATION_ERROR',
  'AUTH_FAILED',
  'FORBIDDEN_ACTION',
  'SESSION_NOT_FOUND',
  'ILLEGAL_STATE_TRANSITION',
  'SESSION_VERSION_CONFLICT',
  'IDEMPOTENCY_KEY_CONFLICT',
  'DUPLICATE_TRANSACTION',
  'INSUFFICIENT_FUNDS',
  'ORIGINAL_TRANSACTION_NOT_FOUND',
  'CURRENCY_MISMATCH',
  'BONUS_BUY_DISABLED',
  'MAX_WIN_REACHED',
  'INTERNAL_ERROR',
]);

// Compile-time guard that our zod enum matches the generated OpenAPI type.
type _ErrorCodeMatch = components['schemas']['ErrorCode'] extends z.infer<typeof errorCodeSchema>
  ? z.infer<typeof errorCodeSchema> extends components['schemas']['ErrorCode']
    ? true
    : never
  : never;
const _errorCodeMatch: _ErrorCodeMatch = true;
void _errorCodeMatch;

const fieldViolationSchema = z.object({
  field: z.string(),
  reason: z.string(),
});

const apiErrorSchema = z.object({
  code: errorCodeSchema,
  message: z.string(),
  httpStatus: z.number().int(),
  traceId: z.string(),
  timestamp: z.string(),
  details: z.array(fieldViolationSchema).optional(),
});

export type ApiError = z.infer<typeof apiErrorSchema>;
export type ErrorCode = z.infer<typeof errorCodeSchema>;
export type FieldViolation = z.infer<typeof fieldViolationSchema>;

/**
 * Domain error raised on every non-2xx HTTP response from the RGS backend.
 */
export class RgsHttpError extends Error {
  readonly code: ErrorCode;
  readonly httpStatus: number;
  readonly traceId: string;
  readonly timestamp: string;
  readonly details: readonly FieldViolation[] | undefined;

  constructor(payload: ApiError) {
    super(payload.message);
    this.name = 'RgsHttpError';
    this.code = payload.code;
    this.httpStatus = payload.httpStatus;
    this.traceId = payload.traceId;
    this.timestamp = payload.timestamp;
    this.details = payload.details;
  }
}

/**
 * Transport-level failure (no HTTP response). Retry semantics for mutations
 * reuse the original `Idempotency-Key` while this error is observed.
 */
export class RgsNetworkError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'RgsNetworkError';
  }
}

interface AxiosLikeError {
  isAxiosError?: boolean;
  message?: string;
  response?: {
    status: number;
    data: unknown;
    headers?: Record<string, unknown>;
  };
}

function readTraceIdHeader(headers: Record<string, unknown> | undefined): string {
  if (!headers) return 'unknown';
  const value = headers['x-trace-id'] ?? headers['X-Trace-Id'];
  return typeof value === 'string' && value.length > 0 ? value : 'unknown';
}

export function mapAxiosError(error: unknown): RgsHttpError | RgsNetworkError {
  const axiosError = error as AxiosLikeError;
  if (axiosError?.isAxiosError) {
    if (axiosError.response) {
      const parsed = apiErrorSchema.safeParse(axiosError.response.data);
      if (parsed.success) {
        return new RgsHttpError(parsed.data);
      }
      return new RgsHttpError({
        code: 'INTERNAL_ERROR',
        message: 'Malformed error response from server',
        httpStatus: axiosError.response.status,
        traceId: readTraceIdHeader(axiosError.response.headers),
        timestamp: new Date().toISOString(),
      });
    }
    return new RgsNetworkError(axiosError.message ?? 'Network error', { cause: error });
  }
  return new RgsNetworkError('Unknown transport failure', { cause: error });
}
