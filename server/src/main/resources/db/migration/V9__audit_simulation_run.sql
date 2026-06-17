CREATE TABLE audit_simulation_run (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(64) NOT NULL UNIQUE,
    requested_by    VARCHAR(64) NOT NULL,
    game_id         VARCHAR(64) NOT NULL,
    math_version    VARCHAR(32) NOT NULL,
    params          JSONB NOT NULL,
    report          JSONB NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_simulation_run_started_at
    ON audit_simulation_run (started_at DESC);

CREATE INDEX idx_audit_simulation_run_game_math
    ON audit_simulation_run (game_id, math_version);
