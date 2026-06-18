import DecimalCtor from 'decimal.js-light';

const DISPLAY_SCALE = 2;

DecimalCtor.set({ rounding: DecimalCtor.ROUND_HALF_UP });

export const SUPPORTED_CURRENCIES = ['EUR', 'USD'] as const;
export type Currency = (typeof SUPPORTED_CURRENCIES)[number];

function assertCurrency(value: string): asserts value is Currency {
  if (!(SUPPORTED_CURRENCIES as readonly string[]).includes(value)) {
    throw new Error(`Unsupported currency: ${value}`);
  }
}

type DecimalLike = InstanceType<typeof DecimalCtor>;

export class Money {
  readonly amount: DecimalLike;
  readonly currency: Currency;

  private constructor(amount: DecimalLike, currency: Currency) {
    this.amount = amount;
    this.currency = currency;
  }

  static fromNumber(value: number, currency: Currency = 'EUR'): Money {
    assertCurrency(currency);
    if (!Number.isFinite(value)) {
      throw new Error(`Invalid money number: ${String(value)}`);
    }
    return new Money(new DecimalCtor(value), currency);
  }

  static fromString(value: string, currency: Currency = 'EUR'): Money {
    assertCurrency(currency);
    return new Money(new DecimalCtor(value), currency);
  }

  static zero(currency: Currency): Money {
    assertCurrency(currency);
    return new Money(new DecimalCtor(0), currency);
  }

  add(other: Money): Money {
    this.assertSameCurrency(other);
    return new Money(this.amount.plus(other.amount), this.currency);
  }

  subtract(other: Money): Money {
    this.assertSameCurrency(other);
    return new Money(this.amount.minus(other.amount), this.currency);
  }

  multiply(factor: number | string | DecimalLike): Money {
    const decimalFactor = factor instanceof DecimalCtor ? factor : new DecimalCtor(factor);
    return new Money(this.amount.times(decimalFactor), this.currency);
  }

  compareTo(other: Money): number {
    this.assertSameCurrency(other);
    return this.amount.cmp(other.amount);
  }

  equals(other: Money): boolean {
    return this.currency === other.currency && this.amount.eq(other.amount);
  }

  format(currency: Currency = this.currency, locale = 'en-US'): string {
    assertCurrency(currency);
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(this.toPlain());
  }

  toPlain(): number {
    return Number(
      this.amount.toDecimalPlaces(DISPLAY_SCALE, DecimalCtor.ROUND_HALF_UP).toFixed(DISPLAY_SCALE),
    );
  }

  toString(): string {
    return this.amount.toString();
  }

  private assertSameCurrency(other: Money): void {
    if (this.currency !== other.currency) {
      throw new Error(`Currency mismatch: ${this.currency} vs ${other.currency}`);
    }
  }
}
