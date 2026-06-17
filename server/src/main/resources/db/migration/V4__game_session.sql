CREATE TABLE IF NOT EXISTS game_session (
    id                            BIGSERIAL     PRIMARY KEY,
    session_id                    VARCHAR(64)   NOT NULL,
    player_id                     VARCHAR(64)   NOT NULL,
    game_id                       VARCHAR(64)   NOT NULL,
    math_version                  VARCHAR(32)   NOT NULL,
    currency                      VARCHAR(3)    NOT NULL,
    current_state                 VARCHAR(32)   NOT NULL,
    current_bet                   NUMERIC(19,4) NOT NULL,
    remaining_free_spins          INTEGER       NOT NULL DEFAULT 0,
    accumulated_free_spins_win    NUMERIC(19,4) NOT NULL DEFAULT 0,
    active_feature_payload        JSONB,
    next_action_allowed           VARCHAR(32),
    session_version               BIGINT        NOT NULL DEFAULT 0,
    created_at                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_game_session_session_id UNIQUE (session_id)
);

CREATE INDEX IF NOT EXISTS idx_game_session_player_updated
    ON game_session (player_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_game_session_player_game
    ON game_session (player_id, game_id);
