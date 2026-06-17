CREATE TABLE IF NOT EXISTS wallet_transaction (
    id                       BIGSERIAL     PRIMARY KEY,
    player_id                VARCHAR(64)   NOT NULL,
    transaction_id           VARCHAR(64)   NOT NULL,
    original_transaction_id  VARCHAR(64),
    session_id               VARCHAR(64),
    round_id                 VARCHAR(64),
    type                     VARCHAR(32)   NOT NULL,
    status                   VARCHAR(16)   NOT NULL,
    amount                   NUMERIC(19,4) NOT NULL,
    amount_minor             BIGINT        NOT NULL,
    currency                 VARCHAR(3)    NOT NULL,
    balance_before           NUMERIC(19,4) NOT NULL,
    balance_after            NUMERIC(19,4) NOT NULL,
    idempotency_key          VARCHAR(128),
    rollback_reason          VARCHAR(32),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallet_tx_transaction_id UNIQUE (transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_wallet_tx_player_created
    ON wallet_transaction (player_id, created_at);

CREATE INDEX IF NOT EXISTS idx_wallet_tx_original
    ON wallet_transaction (original_transaction_id);
