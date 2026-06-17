CREATE TABLE IF NOT EXISTS feature_purchase_event (
    id               BIGSERIAL     PRIMARY KEY,
    player_id        VARCHAR(64)   NOT NULL,
    session_id       VARCHAR(64)   NOT NULL,
    buy_type         VARCHAR(32)   NOT NULL,
    cost             NUMERIC(19,4) NOT NULL,
    cost_minor       BIGINT        NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    idempotency_key  VARCHAR(128),
    resulting_state  VARCHAR(32)   NOT NULL,
    transaction_id   VARCHAR(64),
    bet_size         NUMERIC(19,4) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_feature_purchase_player_created
    ON feature_purchase_event (player_id, created_at);

CREATE INDEX IF NOT EXISTS idx_feature_purchase_session
    ON feature_purchase_event (session_id);
