-- V83: make turnover buffers actually protect venues, without rewriting live schedules.
--
-- V81 shipped the capability with a default of 0, so no existing venue benefits
-- until someone sets a value. The obvious "fix" — backfilling a non-zero default
-- onto every venue — is exactly wrong: it would retroactively widen the occupancy
-- of every future booking already on the calendar and could make bookings a venue
-- has ALREADY SOLD overlap each other. The V81 backstop would then reject the next
-- ordinary status update on those rows. Silently invalidating sold inventory is a
-- worse failure than the gap it closes.
--
-- So the split is:
--   * NEW venues get a protective default at the column level (30 min cleanup).
--   * EXISTING venues keep 0 and are flagged for an explicit operator decision.
--
-- A venue is never silently changed; it is asked.

-- ── 1. Protective default for venues created from here on ───────────────
-- Only the column DEFAULT changes. No UPDATE runs, so not a single existing row
-- is touched.
ALTER TABLE binges
    ALTER COLUMN default_cleanup_minutes SET DEFAULT 30;

COMMENT ON COLUMN binges.default_cleanup_minutes IS
    'Venue-wide default turnover time after every booking. Defaults to 30 for venues created after V83; venues created before keep whatever they had (0 unless set) until an operator decides — see turnover_policy_reviewed_at.';

-- ── 2. Track whether an operator has actually made the decision ─────────
-- NULL = never reviewed. The admin console shows a prompt until it is set, so a
-- venue distributing to channels can be blocked on a real answer rather than on
-- an accident of when it was created.
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS turnover_policy_reviewed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS turnover_policy_reviewed_by BIGINT;

COMMENT ON COLUMN binges.turnover_policy_reviewed_at IS
    'When an operator explicitly confirmed this venue''s setup/cleanup buffers. NULL = never reviewed; the admin console prompts, and distribution go-live should require it. Choosing zero is a valid answer — what matters is that it was chosen.';

-- ── 3. Venues that already configured a buffer have self-evidently decided ──
-- Backfilling the review timestamp for them avoids nagging an operator who has
-- already done the thing we are asking for. Venues still on 0 stay NULL.
UPDATE binges
   SET turnover_policy_reviewed_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
 WHERE turnover_policy_reviewed_at IS NULL
   AND (default_setup_minutes > 0 OR default_cleanup_minutes > 0);

DO $$
DECLARE unreviewed INTEGER;
BEGIN
    SELECT COUNT(*) INTO unreviewed FROM binges WHERE turnover_policy_reviewed_at IS NULL;
    IF unreviewed > 0 THEN
        RAISE NOTICE
            'V83: % venue(s) have not set a turnover policy and remain at 0 minutes. '
            'They are UNCHANGED by design — the admin console will prompt each one. '
            'Do not distribute a venue to a sales channel before its policy is reviewed.',
            unreviewed;
    END IF;
END $$;
