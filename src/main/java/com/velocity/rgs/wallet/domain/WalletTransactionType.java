package com.velocity.rgs.wallet.domain;

/**
 * Canonical wallet transaction types per A.0.1.
 */
public enum WalletTransactionType {
    BET,
    BONUS_BUY,
    WIN,
    FEATURE_WIN,
    /** A progressive pool paid out. Its own type so reconciliation can match it to a jackpot_win
     *  row rather than seeing an unexplained credit the size of the whole pool. */
    JACKPOT_WIN,
    ROLLBACK
}
