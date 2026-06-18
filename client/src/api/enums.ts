import type { components } from '@/api/generated/openapi';

/**
 * Canonical wire enums mirrored verbatim from `client-requirements.md` §C.
 * Adding a new value here without a backend change is forbidden — the
 * compile-time assertions below prove the literal unions match the generated
 * OpenAPI schema in both directions.
 */

export const GameState = {
  BASE_GAME: 'BASE_GAME',
  FREE_SPINS_AWAITING: 'FREE_SPINS_AWAITING',
  FREE_SPINS_LOOP: 'FREE_SPINS_LOOP',
  PICK_COLLECT_AWAITING: 'PICK_COLLECT_AWAITING',
  PICK_COLLECT_LOOP: 'PICK_COLLECT_LOOP',
} as const;
export type GameState = (typeof GameState)[keyof typeof GameState];

export const GameCommand = {
  SPIN: 'SPIN',
  START_FREE_SPINS: 'START_FREE_SPINS',
  START_PICK_COLLECT: 'START_PICK_COLLECT',
  BUY_FEATURE: 'BUY_FEATURE',
  PICK: 'PICK',
} as const;
export type GameCommand = (typeof GameCommand)[keyof typeof GameCommand];

export const FeatureType = {
  FREE_SPINS: 'FREE_SPINS',
  PICK_COLLECT: 'PICK_COLLECT',
} as const;
export type FeatureType = (typeof FeatureType)[keyof typeof FeatureType];

export const BonusBuyType = {
  FREE_SPINS_BUY: 'FREE_SPINS_BUY',
  PICK_COLLECT_BUY: 'PICK_COLLECT_BUY',
} as const;
export type BonusBuyType = (typeof BonusBuyType)[keyof typeof BonusBuyType];

export const PickTileType = {
  CREDITS: 'CREDITS',
  MULTIPLIER: 'MULTIPLIER',
  COLLECT: 'COLLECT',
  BLANK: 'BLANK',
  END: 'END',
} as const;
export type PickTileType = (typeof PickTileType)[keyof typeof PickTileType];

export const PickCollectStatus = {
  IN_PROGRESS: 'IN_PROGRESS',
  COLLECTED: 'COLLECTED',
  ENDED: 'ENDED',
} as const;
export type PickCollectStatus = (typeof PickCollectStatus)[keyof typeof PickCollectStatus];

export const WalletTransactionType = {
  BET: 'BET',
  BONUS_BUY: 'BONUS_BUY',
  WIN: 'WIN',
  FEATURE_WIN: 'FEATURE_WIN',
  ROLLBACK: 'ROLLBACK',
} as const;
export type WalletTransactionType =
  (typeof WalletTransactionType)[keyof typeof WalletTransactionType];

export const WalletTransactionStatus = {
  SUCCESS: 'SUCCESS',
  REJECTED: 'REJECTED',
} as const;
export type WalletTransactionStatus =
  (typeof WalletTransactionStatus)[keyof typeof WalletTransactionStatus];

export const RollbackReason = {
  DOWNSTREAM_FAILURE: 'DOWNSTREAM_FAILURE',
  TECHNICAL_ERROR: 'TECHNICAL_ERROR',
  OPERATOR_CANCEL: 'OPERATOR_CANCEL',
} as const;
export type RollbackReason = (typeof RollbackReason)[keyof typeof RollbackReason];

export const ErrorCode = {
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  AUTH_FAILED: 'AUTH_FAILED',
  FORBIDDEN_ACTION: 'FORBIDDEN_ACTION',
  SESSION_NOT_FOUND: 'SESSION_NOT_FOUND',
  ILLEGAL_STATE_TRANSITION: 'ILLEGAL_STATE_TRANSITION',
  SESSION_VERSION_CONFLICT: 'SESSION_VERSION_CONFLICT',
  IDEMPOTENCY_KEY_CONFLICT: 'IDEMPOTENCY_KEY_CONFLICT',
  DUPLICATE_TRANSACTION: 'DUPLICATE_TRANSACTION',
  INSUFFICIENT_FUNDS: 'INSUFFICIENT_FUNDS',
  ORIGINAL_TRANSACTION_NOT_FOUND: 'ORIGINAL_TRANSACTION_NOT_FOUND',
  CURRENCY_MISMATCH: 'CURRENCY_MISMATCH',
  BONUS_BUY_DISABLED: 'BONUS_BUY_DISABLED',
  MAX_WIN_REACHED: 'MAX_WIN_REACHED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

export const PickStrategy = {
  SEQUENTIAL: 'SEQUENTIAL',
  RANDOM_UNOPENED: 'RANDOM_UNOPENED',
  COLLECT_FIRST: 'COLLECT_FIRST',
} as const;
export type PickStrategy = (typeof PickStrategy)[keyof typeof PickStrategy];

// ---------------------------------------------------------------- assertions
// Bidirectional type-level equality vs. generated OpenAPI schema.
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;

const _gameState: Equal<GameState, components['schemas']['GameState']> = true;
const _gameCommand: Equal<GameCommand, components['schemas']['GameCommand']> = true;
const _featureType: Equal<FeatureType, components['schemas']['FeatureType']> = true;
const _bonusBuyType: Equal<BonusBuyType, components['schemas']['BonusBuyType']> = true;
const _pickTileType: Equal<PickTileType, components['schemas']['PickTileType']> = true;
const _pickCollectStatus: Equal<
  PickCollectStatus,
  components['schemas']['PickCollectStatus']
> = true;
const _walletTransactionType: Equal<
  WalletTransactionType,
  components['schemas']['WalletTransactionType']
> = true;
const _walletTransactionStatus: Equal<
  WalletTransactionStatus,
  components['schemas']['WalletTransactionStatus']
> = true;
const _rollbackReason: Equal<RollbackReason, components['schemas']['RollbackReason']> = true;
const _errorCode: Equal<ErrorCode, components['schemas']['ErrorCode']> = true;
const _pickStrategy: Equal<PickStrategy, components['schemas']['PickStrategy']> = true;

void _gameState;
void _gameCommand;
void _featureType;
void _bonusBuyType;
void _pickTileType;
void _pickCollectStatus;
void _walletTransactionType;
void _walletTransactionStatus;
void _rollbackReason;
void _errorCode;
void _pickStrategy;
