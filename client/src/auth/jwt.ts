import { z } from 'zod';

import type { Currency } from '@/common/money/Money';

/**
 * Decoded JWT claims emitted by the RGS backend. Claim names mirror
 * `server/be-requirements.md` A.6 verbatim: `sub`, `sid`, `cur`, `exp`, `roles`.
 * The client never validates the signature — the server does.
 */
const jwtClaimsSchema = z.object({
  sub: z.string().min(1),
  sid: z.string().min(1),
  cur: z.enum(['EUR', 'USD']),
  exp: z.number().int().positive(),
  roles: z.array(z.string()).default([]),
});

export type JwtClaims = {
  sub: string;
  sid: string;
  cur: Currency;
  exp: number;
  roles: string[];
};

function base64UrlDecode(segment: string): string {
  const padded = segment.replace(/-/g, '+').replace(/_/g, '/');
  const pad = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
  const binary = atob(padded + pad);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder('utf-8').decode(bytes);
}

export function decodeJwtPayload(token: string): JwtClaims {
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) {
    throw new Error('Malformed JWT: expected three dot-separated segments');
  }
  let json: unknown;
  try {
    json = JSON.parse(base64UrlDecode(parts[1]));
  } catch (cause) {
    throw new Error('Malformed JWT: payload is not valid JSON', { cause });
  }
  const parsed = jwtClaimsSchema.safeParse(json);
  if (!parsed.success) {
    const issue = parsed.error.issues[0];
    throw new Error(
      `Malformed JWT claims: ${issue?.path?.join('.') ?? 'payload'} — ${issue?.message ?? 'invalid'}`,
    );
  }
  return parsed.data;
}

export function claimsExpiry(claims: JwtClaims): Date {
  return new Date(claims.exp * 1000);
}
