package com.velocity.rgs.wallet.domain;

/**
 * Canonical rollback reasons per A.0.1.
 */
public enum RollbackReason {
    DOWNSTREAM_FAILURE,
    TECHNICAL_ERROR,
    OPERATOR_CANCEL
}
