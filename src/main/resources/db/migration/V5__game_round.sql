CREATE TABLE IF NOT EXISTS game_round (
    id               BIGSERIAL     PRIMARY KEY,
    session_id       VARCHAR(64)   NOT NULL,
    player_id        VARCHAR(64)   NOT NULL,
    round_id         VARCHAR(64)   NOT NULL,
    game_id          VARCHAR(64)   NOT NULL,
    math_version     VARCHAR(32)   NOT NULL,
    state_context    VARCHAR(32)   NOT NULL,
    bet_amount       NUMERIC(19,4) NOT NULL,
    bet_amount_minor BIGINT        NOT NULL,
    total_win        NUMERIC(19,4) NOT NULL,
    total_win_minor  BIGINT        NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    matrix           JSONB         NOT NULL,
    stop_positions   JSONB         NOT NULL,
    rng_draws        JSONB         NOT NULL,
    win_lines        JSONB,
    reason_codes     JSONB,
    power_bet_active BOOLEAN       NOT NULL DEFAULT FALSE,
    bet_transaction_id VARCHAR(64),
    win_transaction_id VARCHAR(64),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_game_round_round_id UNIQUE (round_id)
);

CREATE INDEX IF NOT EXISTS idx_game_round_player_created
    ON game_round (player_id, created_at);

CREATE INDEX IF NOT EXISTS idx_game_round_session
    ON game_round (session_id);
