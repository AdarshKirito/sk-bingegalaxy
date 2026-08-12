-- V91 — an auto-pause must end when the thing it was punishing ends.
--
-- THE DEFECT ---------------------------------------------------------------
-- V87 taught BingeGracePeriodScheduler to corroborate against real event types before
-- auto-pausing, and repaired the venues that had already been wrongly paused. Both
-- halves were right, and together they still left a venue permanently invisible:
--
--   * the sweep's heal path stamped first_event_created_at and moved on. It never set
--     `active` back to TRUE. Worse, stamping the flag removes the venue from the
--     candidate set, so no later sweep ever looks at it again.
--   * recordFirstEventIfNeeded did the same on the ordinary path: creating an event
--     type marked the venue operational and left it paused.
--
-- So the auto-pause was permanent. A venue paused at the 24-hour mark for having no
-- events stayed out of customer discovery after it had a full catalogue — its admin
-- looking at thirteen event types and a venue nobody could book. V87's UPDATE fixed
-- the rows that existed the day it ran; nothing fixed a row that reached that state
-- afterwards, and the ordinary "approve, forget for a day, then set up the venue"
-- sequence reaches it every time.
--
-- The application fix (BingeService.liftAutoPause, called from both the sweep and the
-- event-type hook) stops new occurrences. This migration repairs the rows already in
-- that state, and extends V87's trigger so the write paths that bypass the service —
-- DataSeeder being the one that caused the original incident — are covered too.
--
-- WHAT THIS WILL NOT DO ----------------------------------------------------
-- It must not resurrect a venue an operator paused on purpose. auto_deactivated_at is
-- the discriminator: only the sweep writes it. That was ALMOST true before today —
-- BingeService.toggleBinge flipped `active` without clearing the timestamp, so a venue
-- that had been auto-paused, manually re-activated, and later deliberately paused still
-- carried it. toggleBinge now clears it on every manual transition, which makes the
-- discriminator honest from here on.
--
-- For rows written BEFORE that fix the ambiguity cannot be resolved from the data, so
-- this migration reports how many it touched rather than repairing silently. Review the
-- NOTICE if any venue is expected to be intentionally paused.

DO $$
DECLARE lifted INTEGER;
BEGIN
    UPDATE binges b
    SET active = TRUE,
        auto_deactivated_at = NULL,
        grace_warning_sent_at = NULL
    WHERE b.auto_deactivated_at IS NOT NULL
      AND b.active = FALSE
      AND EXISTS (SELECT 1 FROM event_types e WHERE e.binge_id = b.id);

    GET DIAGNOSTICS lifted = ROW_COUNT;

    IF lifted > 0 THEN
        -- Deliberately louder than V87's silent repair: V87 could point at a specific
        -- defect (the seeder never stamped the flag) and prove the pause was wrong.
        -- Here the population also includes venues that were paused CORRECTLY and later
        -- given an event type, which is a policy change rather than a bug fix — adding
        -- an event type now lifts the pause instead of requiring a separate manual
        -- re-activation the notification asked for and many admins never performed.
        RAISE WARNING 'V91: lifted a stale auto-pause on % venue(s) that have event types. '
            'Each is now visible to customers again. If any of them was meant to stay '
            'paused, pause it from the console — that path now records the decision as '
            'manual and this repair will never touch it again.', lifted;
    ELSE
        RAISE NOTICE 'V91: no venue was holding a stale auto-pause';
    END IF;
END $$;

-- ── Backstop ───────────────────────────────────────────────────────────────
-- V87's trigger stamps first_event_created_at on any INSERT into event_types, so no
-- write path can leave a venue with events and a NULL flag. It is extended here to lift
-- the auto-pause in the same statement, for exactly the same reason: the application is
-- now correct on every path it owns, and the database should refuse to hold the
-- contradictory state — "paused for having no events" alongside "has events" —
-- regardless of which code inserted the row.
--
-- Predicated on auto_deactivated_at IS NOT NULL, so a manually paused venue is never
-- re-published by an event type being added to it.
CREATE OR REPLACE FUNCTION trg_stamp_binge_first_event() RETURNS TRIGGER AS $$
BEGIN
    UPDATE binges
       SET first_event_created_at = COALESCE(first_event_created_at, NOW()),
           active = CASE WHEN auto_deactivated_at IS NOT NULL THEN TRUE ELSE active END,
           grace_warning_sent_at = CASE WHEN auto_deactivated_at IS NOT NULL
                                        THEN NULL ELSE grace_warning_sent_at END,
           auto_deactivated_at = NULL
     WHERE id = NEW.binge_id
       AND (first_event_created_at IS NULL OR auto_deactivated_at IS NOT NULL);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION trg_stamp_binge_first_event() IS
    'Backstop for BingeGracePeriodScheduler: any INSERT into event_types marks the binge operational AND lifts an automatic pause, so no write path can leave a venue paused for having no events while it has them. Never touches a manual pause (auto_deactivated_at IS NULL). See V87, V91.';

COMMENT ON COLUMN binges.auto_deactivated_at IS
    'Set ONLY by BingeGracePeriodScheduler when it auto-pauses a venue for having no event types. Cleared when the pause is lifted and on any manual toggle. This is the sole discriminator between an automatic pause (repairable) and a deliberate one (never touched) — any code that changes `active` must keep it accurate.';
