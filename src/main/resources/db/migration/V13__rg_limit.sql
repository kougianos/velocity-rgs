-- Responsible Gaming limits, one row per player (§4.2).
--
-- Keyed by player_id, not session_id, and that is the whole point: a limit a player can shed by closing
-- the tab is not a limit. Session duration, loss and wager limits are consumed across every game and
-- every session the player opens, and a cool-off or self-exclusion outlives all of them.
--
-- Every limit column is NULLABLE and null means "not set". A player who has never opened the RG panel
-- has no row at all, which reads identically and costs nothing - the row is created on first write.
--
-- Consumption is deliberately NOT stored here. Wagered, won and net loss are derived from
-- wallet_transaction, which is already the audited record of every stake and payout and already carries
-- (player_id, created_at) as an index. Two counters that must agree with the ledger eventually disagree
-- with it; deriving them means the limit is enforced against the same rows an auditor would read.
CREATE TABLE IF NOT EXISTS rg_limit (
    player_id                VARCHAR(64)   NOT NULL,

    -- Player-set limits. Null = unset.
    session_limit_minutes    INTEGER,
    loss_limit               NUMERIC(19,4),
    wager_limit              NUMERIC(19,4),
    reality_check_minutes    INTEGER,

    -- Start of the window loss_limit and wager_limit are measured over, and of the current play
    -- session that session_limit_minutes is measured over. Separate because they roll differently: the
    -- period is a fixed accounting window, the session ends when the player stops playing.
    period_started_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    session_started_at       TIMESTAMPTZ,
    last_activity_at         TIMESTAMPTZ,
    last_reality_check_at    TIMESTAMPTZ,

    -- Blocks. cool_off_until is a timestamp because a cool-off ends by itself; self_excluded_at has no
    -- paired "until" because self-exclusion does not, and storing an expiry would invite one.
    cool_off_until           TIMESTAMPTZ,
    self_excluded_at         TIMESTAMPTZ,

    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id)
);

-- The policy check reads a player's blocks on every staked action, so the two that gate play are worth
-- an index even at demo scale: it is the one query that runs inside the money transaction.
CREATE INDEX IF NOT EXISTS idx_rg_limit_blocks
    ON rg_limit (cool_off_until, self_excluded_at);
