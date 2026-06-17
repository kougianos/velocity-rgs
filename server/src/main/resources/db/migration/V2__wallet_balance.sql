CREATE TABLE IF NOT EXISTS wallet_balance (
    player_id      VARCHAR(64)   NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    balance        NUMERIC(19,4) NOT NULL,
    balance_minor  BIGINT        NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id)
);

CREATE INDEX IF NOT EXISTS idx_wallet_balance_currency ON wallet_balance (currency);
