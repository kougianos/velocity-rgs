CREATE TABLE IF NOT EXISTS pick_collect_snapshot (
    id                BIGSERIAL     PRIMARY KEY,
    session_id        VARCHAR(64)   NOT NULL,
    round_id          VARCHAR(64),
    board_seed        VARCHAR(64),
    board             JSONB         NOT NULL,
    opened_positions  JSONB         NOT NULL,
    final_win         NUMERIC(19,4),
    final_win_minor   BIGINT,
    status            VARCHAR(16)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pick_collect_session
    ON pick_collect_snapshot (session_id);

CREATE INDEX IF NOT EXISTS idx_pick_collect_round
    ON pick_collect_snapshot (round_id);
