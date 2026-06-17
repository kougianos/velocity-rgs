CREATE TABLE IF NOT EXISTS audit_reconciliation_finding (
    id                BIGSERIAL     PRIMARY KEY,
    player_id         VARCHAR(64)   NOT NULL,
    bucket_start      TIMESTAMPTZ   NOT NULL,
    bucket_end        TIMESTAMPTZ   NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    expected_debit    NUMERIC(19,4) NOT NULL,
    actual_debit      NUMERIC(19,4) NOT NULL,
    expected_credit   NUMERIC(19,4) NOT NULL,
    actual_credit     NUMERIC(19,4) NOT NULL,
    discrepancy       NUMERIC(19,4) NOT NULL,
    discrepancy_kind  VARCHAR(32)   NOT NULL,
    detail            TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_finding_player_bucket UNIQUE (player_id, bucket_start, discrepancy_kind)
);

CREATE INDEX IF NOT EXISTS idx_audit_finding_player_created
    ON audit_reconciliation_finding (player_id, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_finding_bucket
    ON audit_reconciliation_finding (bucket_start);
