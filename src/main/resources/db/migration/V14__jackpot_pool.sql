-- Progressive jackpot pools (§2).
--
-- One row per (tier, currency), and the currency half is load-bearing: a pool is an amount of money, so
-- the EUR Mega and the USD Mega are two different prizes rather than one prize converted at read time.
-- Converting on read would mean the figure on the lobby moved when an exchange rate did, which is not a
-- thing a jackpot may do.
--
-- Postgres is the pool of record. Redis appears later as a read cache for the lobby ticker and never as
-- the source: a pool is money owed to whoever wins it, and money does not live in a cache that is
-- allowed to be evicted. That split is the whole architecture of this feature.
CREATE TABLE IF NOT EXISTS jackpot_pool (
    tier            VARCHAR(16)   NOT NULL,
    currency        VARCHAR(3)    NOT NULL,

    -- What the pool is worth right now. NUMERIC(19,4) to match wallet_balance exactly - a jackpot is
    -- credited to a wallet, and a prize that cannot be represented in the account it pays into is a bug
    -- waiting for the largest possible win to expose it.
    amount          NUMERIC(19,4) NOT NULL,

    -- The floor the pool returns to when it is won. Stored on the row rather than read from config at
    -- award time so a pool reseeds to the value that was actually in force for it, and so this table
    -- alone says what the pool is worth and what it can never drop below.
    seed_amount     NUMERIC(19,4) NOT NULL,

    -- Optimistic locking. Contributions land inside the spin's money transaction, so concurrent spins
    -- from different players contend on exactly this row and the version is what serialises them.
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tier, currency),

    CONSTRAINT chk_jackpot_pool_amount_at_least_seed CHECK (amount >= seed_amount),
    CONSTRAINT chk_jackpot_pool_seed_positive        CHECK (seed_amount > 0)
);

-- The lobby reads every tier for one currency on each load, which is this index exactly.
CREATE INDEX IF NOT EXISTS idx_jackpot_pool_currency ON jackpot_pool (currency);

-- Day-zero pools, at seed. A fresh install has jackpots that have never been contributed to, and
-- showing them at their seed value is what that honestly looks like - they start moving when spins
-- start contributing. The ladder is x10 per tier, which is what makes four tiers read as four
-- different prizes rather than four similar ones.
--
-- ON CONFLICT DO NOTHING so re-running against a database whose pools have already grown cannot reset
-- them: this statement seeds a new install and is a no-op on every existing one.
INSERT INTO jackpot_pool (tier, currency, amount, seed_amount) VALUES
    ('MINI',  'EUR',    10.0000,    10.0000),
    ('MINOR', 'EUR',   100.0000,   100.0000),
    ('MAJOR', 'EUR',  1000.0000,  1000.0000),
    ('MEGA',  'EUR', 10000.0000, 10000.0000)
ON CONFLICT (tier, currency) DO NOTHING;
