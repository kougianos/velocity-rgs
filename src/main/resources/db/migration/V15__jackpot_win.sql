-- Progressive jackpot awards (§2).
--
-- This table is not only an audit trail; it is the exactly-once guarantee written down. A jackpot is
-- the one payout in the system large enough that awarding it twice is unrecoverable, so "exactly once"
-- is enforced in three independent places and this is the last of them:
--
--   1. The Idempotency-Key layer replays a retried spin's stored response without re-running the spin
--      body at all, so a retry never reaches the award code.
--   2. The pool reset is a compare-and-swap on jackpot_pool.version, so two spins racing for the same
--      tier cannot both win it - the loser's CAS matches no row and it awards nothing.
--   3. The unique constraint below. If the first two ever fail, this turns a double award into a
--      failed transaction that rolls back, rather than into money that has left twice.
--
-- Belt and braces on purpose. The first two are application logic and could be refactored wrong; the
-- third cannot be, and it is the one an auditor would trust.
CREATE TABLE IF NOT EXISTS jackpot_win (
    win_id          VARCHAR(64)   NOT NULL,

    -- One round wins at most one jackpot, which is what makes this the exactly-once key.
    round_id        VARCHAR(64)   NOT NULL,
    player_id       VARCHAR(64)   NOT NULL,
    session_id      VARCHAR(64)   NOT NULL,
    game_id         VARCHAR(64)   NOT NULL,

    tier            VARCHAR(16)   NOT NULL,
    currency        VARCHAR(3)    NOT NULL,

    -- What the pool held at the moment it was won, and what the player was paid. Recorded here rather
    -- than derived later because the pool row is reset to its seed in the same transaction - after
    -- which nothing else in the schema remembers how big the prize was.
    amount          NUMERIC(19,4) NOT NULL,
    -- The floor it was reset to, so the row alone explains the pool's jump backwards.
    seed_amount     NUMERIC(19,4) NOT NULL,

    -- The wallet transaction that paid it. Unique for the same reason round_id is: one prize, one
    -- credit. Reconciliation (§2, next task) joins on this to explain the balance movement.
    tx_id           VARCHAR(96)   NOT NULL,

    won_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (win_id),
    CONSTRAINT uq_jackpot_win_round UNIQUE (round_id),
    CONSTRAINT uq_jackpot_win_tx    UNIQUE (tx_id),
    CONSTRAINT chk_jackpot_win_amount_positive CHECK (amount > 0)
);

-- The player-facing lookup: "show me my jackpot wins", newest first.
CREATE INDEX IF NOT EXISTS idx_jackpot_win_player ON jackpot_win (player_id, won_at DESC);

-- Reconciliation walks wins by time to match them against wallet credits in the same window.
CREATE INDEX IF NOT EXISTS idx_jackpot_win_won_at ON jackpot_win (won_at);
