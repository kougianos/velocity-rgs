-- Hold & Spin respins are rounds, but not the same *kind* of round as a spin: a respin re-draws only
-- the unlocked cells, so reconstructing it needs the coins that were already held going in. Without
-- that input the recorded draws are meaningless - they have neither the count nor the bounds of a
-- full reel spin, and replay fails on the first draw.
--
-- round_kind discriminates the two, and feature_context carries the input state a non-SPIN round needs
-- to stand on its own. Both default so every row written before this migration keeps replaying
-- unchanged: an existing round is a SPIN with no extra context, which is exactly what it was.
ALTER TABLE game_round
    ADD COLUMN IF NOT EXISTS round_kind VARCHAR(16) NOT NULL DEFAULT 'SPIN';

ALTER TABLE game_round
    ADD COLUMN IF NOT EXISTS feature_context JSONB;

-- Rows written between Hold & Spin shipping and this migration are respins that the DEFAULT above
-- would misfile as SPIN, which sends them down the reel-spin replay path and fails on the first draw.
-- They are identifiable by the round-id prefix SlotEngineService.respinSpin assigns. Classifying them
-- does not make them replayable - feature_context was never captured for them, so the coins held going
-- in are simply not recorded - but it does let the replay endpoint say so plainly instead of blowing
-- up. There is nothing to backfill feature_context from: the session payload it came from has long
-- since moved on.
UPDATE game_round SET round_kind = 'RESPIN'
 WHERE round_id LIKE 'rsp-%' AND round_kind = 'SPIN';
