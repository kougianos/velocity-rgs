CREATE TABLE IF NOT EXISTS blackjack_round (
    id                 BIGSERIAL     PRIMARY KEY,
    session_id         VARCHAR(64)   NOT NULL,
    player_id          VARCHAR(64)   NOT NULL,
    round_id           VARCHAR(64)   NOT NULL,
    game_id            VARCHAR(64)   NOT NULL,
    math_version       VARCHAR(32)   NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    bet                NUMERIC(19,4) NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    shoe               JSONB         NOT NULL,
    player_hands       JSONB         NOT NULL,
    dealer_hand        JSONB         NOT NULL,
    outcomes           JSONB         NOT NULL,
    total_bet          NUMERIC(19,4) NOT NULL,
    total_bet_minor    BIGINT        NOT NULL,
    total_win          NUMERIC(19,4) NOT NULL,
    total_win_minor    BIGINT        NOT NULL,
    rng_draws          JSONB         NOT NULL,
    bet_transaction_id VARCHAR(64),
    win_transaction_id VARCHAR(64),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_blackjack_round_round_id UNIQUE (round_id)
);

CREATE INDEX IF NOT EXISTS idx_blackjack_round_player_created
    ON blackjack_round (player_id, created_at);

-- Fast lookup of the single active (unsettled) round for a session.
CREATE INDEX IF NOT EXISTS idx_blackjack_round_session_status
    ON blackjack_round (session_id, status);
