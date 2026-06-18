import { describe, expect, it } from 'vitest';

import { Money } from './Money';

describe('Money', () => {
  it('parity with backend BigDecimal: 0.1 + 0.2 formats to €0.30 in en-GB', () => {
    const result = Money.fromNumber(0.1).add(Money.fromNumber(0.2));
    expect(result.format('EUR', 'en-GB')).toBe('€0.30');
  });

  it('subtract preserves currency and precision', () => {
    const result = Money.fromString('100.10', 'EUR').subtract(Money.fromString('1.50', 'EUR'));
    expect(result.toString()).toBe('98.6');
    expect(result.toPlain()).toBe(98.6);
  });

  it('multiply by an integer', () => {
    expect(Money.fromString('1.25', 'EUR').multiply(4).toPlain()).toBe(5);
  });

  it('HALF_UP rounding when formatting', () => {
    expect(Money.fromString('0.125', 'EUR').format('EUR', 'en-GB')).toBe('€0.13');
    expect(Money.fromString('0.124', 'EUR').format('EUR', 'en-GB')).toBe('€0.12');
  });

  it('compareTo and equals respect currency', () => {
    const a = Money.fromString('5.00', 'EUR');
    const b = Money.fromString('5.00', 'EUR');
    const c = Money.fromString('6.00', 'EUR');
    expect(a.equals(b)).toBe(true);
    expect(a.compareTo(c)).toBeLessThan(0);
    expect(c.compareTo(a)).toBeGreaterThan(0);
  });

  it('throws on cross-currency arithmetic', () => {
    expect(() => Money.fromNumber(1, 'EUR').add(Money.fromNumber(1, 'USD'))).toThrow(
      /Currency mismatch/,
    );
  });

  it('rejects unsupported currency', () => {
    // GBP intentionally rejected — supported set is EUR/USD only.
    expect(() => Money.fromNumber(1, 'GBP' as unknown as 'EUR')).toThrow(
      /Unsupported currency/,
    );
  });

  it('rejects non-finite input', () => {
    expect(() => Money.fromNumber(Number.NaN)).toThrow(/Invalid money number/);
    expect(() => Money.fromNumber(Number.POSITIVE_INFINITY)).toThrow(/Invalid money number/);
  });

  it('zero(currency) returns 0 in the requested currency', () => {
    const z = Money.zero('USD');
    expect(z.currency).toBe('USD');
    expect(z.toPlain()).toBe(0);
  });
});
