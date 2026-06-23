CREATE TABLE IF NOT EXISTS roulette_round (
    id                 BIGSERIAL     PRIMARY KEY,
    session_id         VARCHAR(64)   NOT NULL,
    player_id          VARCHAR(64)   NOT NULL,
    round_id           VARCHAR(64)   NOT NULL,
    game_id            VARCHAR(64)   NOT NULL,
    math_version       VARCHAR(32)   NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    winning_number     INTEGER       NOT NULL,
    winning_color      VARCHAR(8)    NOT NULL,
    total_bet          NUMERIC(19,4) NOT NULL,
    total_bet_minor    BIGINT        NOT NULL,
    total_win          NUMERIC(19,4) NOT NULL,
    total_win_minor    BIGINT        NOT NULL,
    bets               JSONB         NOT NULL,
    winning_bets       JSONB         NOT NULL,
    rng_draws          JSONB         NOT NULL,
    bet_transaction_id VARCHAR(64),
    win_transaction_id VARCHAR(64),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_roulette_round_round_id UNIQUE (round_id)
);

CREATE INDEX IF NOT EXISTS idx_roulette_round_player_created
    ON roulette_round (player_id, created_at);

CREATE INDEX IF NOT EXISTS idx_roulette_round_session
    ON roulette_round (session_id);
