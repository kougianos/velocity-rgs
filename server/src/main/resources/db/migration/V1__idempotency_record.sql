CREATE TABLE IF NOT EXISTS idempotency_record (
    scope          VARCHAR(128) NOT NULL,
    key            VARCHAR(128) NOT NULL,
    payload_hash   VARCHAR(64)  NOT NULL,
    response_body  JSONB,
    status_code    INTEGER      NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_record (expires_at);
