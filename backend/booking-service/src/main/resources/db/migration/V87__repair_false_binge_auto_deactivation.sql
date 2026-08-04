-- V87 — repair venues that the grace-period sweep auto-paused despite having events.
--
-- THE DEFECT ---------------------------------------------------------------
-- V34 added binges.first_event_created_at and BingeGracePeriodScheduler, which
-- auto-deactivates an APPROVED binge that has not created an event type within 24
-- hours of approval. The sweep decided purely on that flag and never checked whether
-- event types actually existed.
--
-- Two write paths create event types. BookingService.createEventType stamped the flag
-- (its own inlined copy of BingeService.recordFirstEventIfNeeded, which was left with
-- zero callers). DataSeeder.seedEventTypes did not — and it runs on EVERY boot for
-- EVERY binge. So any venue whose catalogue came from the seeder ended up with a full
-- 13-event catalogue and a NULL flag, and was pulled out of customer discovery a day
-- after approval.
--
-- Observed in the development database: 5 of 6 binges, active = false,
-- auto_deactivated_at set, 13 event types each.
--
-- V34's backfill covered the rows that existed in 2025; it was one-shot, so everything
-- created since by the seeder path is exposed. The application fix (the sweep now
-- corroborates against event_types and heals the flag) stops new damage but cannot
-- undo what already happened — an auto-paused venue stays invisible until something
-- sets active back to true.
--
-- WHAT THIS MIGRATION WILL NOT DO ------------------------------------------
-- It must not resurrect a venue an ADMIN paused on purpose. The two cases are
-- distinguishable: auto_deactivated_at IS NOT NULL is written only by the sweep, and
-- by nothing else in the codebase. A manual pause leaves it NULL. Every UPDATE below
-- is therefore predicated on auto_deactivated_at IS NOT NULL.
--
-- It also does not re-notify. The admin and super-admin pool already received a
-- CRITICAL "Binge auto-paused" notification; a silent repair is better than a second
-- alarm about a state that no longer exists.

-- ── 1. Reverse the false auto-pauses ───────────────────────────────────────
-- THIS RUNS FIRST, and the ordering is the whole correctness argument.
--
-- Two populations both end up as "auto_deactivated_at IS NOT NULL and event types
-- exist", and only one of them should be reactivated:
--
--   (a) WRONGLY paused. The seeder created the catalogue without stamping the flag, so
--       first_event_created_at is still NULL. Never should have been paused.
--   (b) CORRECTLY paused, then given an event afterwards. The admin used the UI, which
--       goes through createEventType and DOES stamp the flag — so it is NOT NULL. The
--       notification these admins received says "add an event type and re-activate it",
--       so re-activation is theirs to perform, not ours to assume.
--
-- first_event_created_at IS NULL is what separates them, which is why the reactivation
-- has to happen BEFORE step 2 heals that column. Healing first would erase the
-- distinction and silently republish venues an operator had chosen to leave paused.
UPDATE binges b
SET active = TRUE,
    auto_deactivated_at = NULL,
    grace_warning_sent_at = NULL
WHERE b.auto_deactivated_at IS NOT NULL
  AND b.first_event_created_at IS NULL
  AND EXISTS (SELECT 1 FROM event_types e WHERE e.binge_id = b.id);

-- ── 2. Heal the flag wherever the fact contradicts it ──────────────────────
-- Same statement as V34's backfill, re-run for everything created since.
-- b.created_at, not the event's: event_types has no created_at column, so the binge's
-- own creation time is the closest honest answer available. It is only ever read as
-- "is this set", never as a duration, so the imprecision costs nothing.
UPDATE binges b
SET first_event_created_at = COALESCE(b.first_event_created_at, b.created_at)
WHERE b.first_event_created_at IS NULL
  AND EXISTS (SELECT 1 FROM event_types e WHERE e.binge_id = b.id);

-- ── 3. Guardrail ───────────────────────────────────────────────────────────
-- A binge with event types must never again carry a NULL first_event_created_at.
-- Expressed as a trigger rather than a CHECK because the condition spans two tables,
-- which a CHECK cannot do. This is the same belt-and-braces posture as the V81
-- occupancy backstop: the application now stamps correctly AND heals stale flags, and
-- the database refuses to hold the contradictory state regardless.
CREATE OR REPLACE FUNCTION trg_stamp_binge_first_event() RETURNS TRIGGER AS $$
BEGIN
    UPDATE binges
       SET first_event_created_at = COALESCE(first_event_created_at, NOW())
     WHERE id = NEW.binge_id
       AND first_event_created_at IS NULL;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- CREATE OR REPLACE (PostgreSQL 14+) rather than remove-then-recreate. Removing a
-- trigger is on the destructive-pattern list in scripts/check-migration-safety.sh,
-- and rightly so. Replacing in place is also atomic — there is no window in which
-- event_types is left without a backstop.
CREATE OR REPLACE TRIGGER event_type_stamps_binge_operational
    AFTER INSERT ON event_types
    FOR EACH ROW
    EXECUTE FUNCTION trg_stamp_binge_first_event();

COMMENT ON FUNCTION trg_stamp_binge_first_event() IS
    'Backstop for BingeGracePeriodScheduler: any INSERT into event_types marks the binge operational, so no write path can leave a venue with events and a NULL flag. See V87.';
