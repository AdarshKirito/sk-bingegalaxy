-- V81: setup / cleanup turnover buffers (distribution gap G1, and a live
-- direct-booking defect).
--
-- Before this migration, occupancy was the raw booking interval
-- [start, start + duration). Two parties could therefore be sold
-- 19:00-22:00 and 22:00-01:00 in the SAME room with zero reset time —
-- physically undeliverable for a decorated celebration space. The failure is
-- silent: nothing rejects it, and staff discover it on the night.
--
-- The primitive changes from "booking interval" to OCCUPANCY WINDOW:
--
--     [ start - setup_minutes , start + duration + cleanup_minutes )
--
-- and a conflict is an overlap of two occupancy windows, not two intervals.
--
-- Inheritance (mirrors how binges.open_time/close_time already work — NULL on
-- the narrower scope means "inherit the wider one"):
--     event_types.setup_minutes  NULL  -> binges.default_setup_minutes
--     event_types.cleanup_minutes NULL -> binges.default_cleanup_minutes
--
-- Snapshotting: the resolved values are copied onto bookings and slot_holds at
-- creation time, exactly like venue_room_price and rate_code_name already are.
-- Without this, editing an event type would retroactively rewrite the occupancy
-- of historical bookings and the V75 backstop would start disagreeing with the
-- application over rows that were legal when they were written.

-- ── 1. Venue-level defaults ─────────────────────────────────────────────
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS default_setup_minutes   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS default_cleanup_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE binges
    DROP CONSTRAINT IF EXISTS ck_binge_default_setup_minutes,
    DROP CONSTRAINT IF EXISTS ck_binge_default_cleanup_minutes;
ALTER TABLE binges
    ADD CONSTRAINT ck_binge_default_setup_minutes
        CHECK (default_setup_minutes BETWEEN 0 AND 240),
    ADD CONSTRAINT ck_binge_default_cleanup_minutes
        CHECK (default_cleanup_minutes BETWEEN 0 AND 240);

COMMENT ON COLUMN binges.default_setup_minutes IS
    'Venue-wide default prep time reserved BEFORE each booking. Event types may override; NULL there inherits this.';
COMMENT ON COLUMN binges.default_cleanup_minutes IS
    'Venue-wide default reset/turnover time reserved AFTER each booking. Event types may override; NULL there inherits this.';

-- ── 2. Per-event-type override (NULL = inherit the venue default) ───────
ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS setup_minutes   INTEGER,
    ADD COLUMN IF NOT EXISTS cleanup_minutes INTEGER;

ALTER TABLE event_types
    DROP CONSTRAINT IF EXISTS ck_event_type_setup_minutes,
    DROP CONSTRAINT IF EXISTS ck_event_type_cleanup_minutes;
ALTER TABLE event_types
    ADD CONSTRAINT ck_event_type_setup_minutes
        CHECK (setup_minutes IS NULL OR setup_minutes BETWEEN 0 AND 240),
    ADD CONSTRAINT ck_event_type_cleanup_minutes
        CHECK (cleanup_minutes IS NULL OR cleanup_minutes BETWEEN 0 AND 240);

COMMENT ON COLUMN event_types.setup_minutes IS
    'Prep time before this event type. NULL inherits binges.default_setup_minutes.';
COMMENT ON COLUMN event_types.cleanup_minutes IS
    'Turnover time after this event type. NULL inherits binges.default_cleanup_minutes.';

-- ── 3. Immutable snapshot on the reservation itself ─────────────────────
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS setup_minutes   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cleanup_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS ck_booking_setup_minutes,
    DROP CONSTRAINT IF EXISTS ck_booking_cleanup_minutes;
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_setup_minutes
        CHECK (setup_minutes BETWEEN 0 AND 240),
    ADD CONSTRAINT ck_booking_cleanup_minutes
        CHECK (cleanup_minutes BETWEEN 0 AND 240);

COMMENT ON COLUMN bookings.setup_minutes IS
    'Snapshot of the resolved setup buffer at booking time. Never recomputed — historical occupancy must stay reproducible.';
COMMENT ON COLUMN bookings.cleanup_minutes IS
    'Snapshot of the resolved cleanup buffer at booking time. Never recomputed.';

ALTER TABLE slot_holds
    ADD COLUMN IF NOT EXISTS setup_minutes   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cleanup_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE slot_holds
    DROP CONSTRAINT IF EXISTS ck_slot_hold_setup_minutes,
    DROP CONSTRAINT IF EXISTS ck_slot_hold_cleanup_minutes;
ALTER TABLE slot_holds
    ADD CONSTRAINT ck_slot_hold_setup_minutes
        CHECK (setup_minutes BETWEEN 0 AND 240),
    ADD CONSTRAINT ck_slot_hold_cleanup_minutes
        CHECK (cleanup_minutes BETWEEN 0 AND 240);

-- ── 4. Legacy duration_minutes backfill (P1-C) ──────────────────────────
-- The V75 backstop falls back to duration_hours * 60 when duration_minutes is
-- NULL or 0. Every current write path sets duration_minutes, but duration_hours
-- is stored as an integer-truncated durMin/60 — so any legacy row that predates
-- duration_minutes and ran e.g. 90 minutes is counted by the trigger as 60. That
-- under-count is in the oversell direction. Normalise the data so the fallback
-- can never be reached with a wrong value.
UPDATE bookings
   SET duration_minutes = GREATEST(duration_hours, 1) * 60
 WHERE duration_minutes IS NULL
    OR duration_minutes = 0;

-- ── 5. V75 backstop, rewritten for occupancy windows ────────────────────
-- The trigger must mirror the application's semantics EXACTLY. If the app
-- widens a window and the trigger does not, the trigger rejects writes the app
-- considers legal (false positive, outage). If the trigger widens and the app
-- does not, the app admits oversells the trigger then blocks at commit time —
-- also an outage, just later. Both sides move together, here.
CREATE OR REPLACE FUNCTION booking_occupancy_backstop() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    cap         INTEGER;
    new_start   INTEGER;
    new_end     INTEGER;
    overlapping INTEGER;
    has_rooms   BOOLEAN;
BEGIN
    IF NEW.status IN ('CANCELLED', 'NO_SHOW') THEN
        RETURN NEW;
    END IF;

    -- Status-only transitions of an already-active booking (e.g. PENDING →
    -- CONFIRMED) don't change what it occupies — it is already part of the
    -- count. Re-checking would falsely reject the confirm when the room is
    -- exactly at capacity including this booking. Buffers join the identity
    -- comparison: changing them DOES change occupancy.
    IF TG_OP = 'UPDATE'
       AND OLD.status NOT IN ('CANCELLED', 'NO_SHOW')
       AND OLD.venue_room_id IS NOT DISTINCT FROM NEW.venue_room_id
       AND OLD.booking_date = NEW.booking_date
       AND OLD.start_time = NEW.start_time
       AND OLD.duration_minutes IS NOT DISTINCT FROM NEW.duration_minutes
       AND OLD.duration_hours = NEW.duration_hours
       AND OLD.setup_minutes = NEW.setup_minutes
       AND OLD.cleanup_minutes = NEW.cleanup_minutes THEN
        RETURN NEW;
    END IF;

    -- Occupancy window of the incoming row, buffers included.
    new_start := EXTRACT(HOUR FROM NEW.start_time)::int * 60
               + EXTRACT(MINUTE FROM NEW.start_time)::int
               - COALESCE(NEW.setup_minutes, 0);
    new_end := EXTRACT(HOUR FROM NEW.start_time)::int * 60
             + EXTRACT(MINUTE FROM NEW.start_time)::int
             + GREATEST(COALESCE(NULLIF(NEW.duration_minutes, 0), NEW.duration_hours * 60), 1)
             + COALESCE(NEW.cleanup_minutes, 0);

    IF NEW.venue_room_id IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended('booking-room-backstop:' || NEW.venue_room_id || ':' || NEW.booking_date, 0));

        SELECT GREATEST(vr.capacity, 1) INTO cap
        FROM venue_rooms vr WHERE vr.id = NEW.venue_room_id;
        IF cap IS NULL THEN
            RETURN NEW; -- dangling room reference: FK owns that problem
        END IF;

        SELECT COUNT(*) INTO overlapping
        FROM bookings b
        WHERE b.venue_room_id = NEW.venue_room_id
          AND b.booking_date = NEW.booking_date
          AND b.id <> NEW.id
          AND b.status NOT IN ('CANCELLED', 'NO_SHOW')
          -- existing occupancy start < new occupancy end
          AND (EXTRACT(HOUR FROM b.start_time)::int * 60
               + EXTRACT(MINUTE FROM b.start_time)::int
               - COALESCE(b.setup_minutes, 0)) < new_end
          -- existing occupancy end > new occupancy start
          AND (EXTRACT(HOUR FROM b.start_time)::int * 60
               + EXTRACT(MINUTE FROM b.start_time)::int
               + CASE
                   WHEN b.status = 'COMPLETED' AND b.actual_used_minutes IS NOT NULL
                       THEN ((b.actual_used_minutes + 29) / 30) * 30
                   ELSE GREATEST(COALESCE(NULLIF(b.duration_minutes, 0), b.duration_hours * 60), 1)
                 END
               + COALESCE(b.cleanup_minutes, 0)) > new_start;

        IF overlapping >= cap THEN
            RAISE EXCEPTION
                'ROOM_OCCUPANCY_BACKSTOP: room % on % occupancy window [%,%) already has % overlapping active booking(s), capacity % — a write path bypassed the booking slot lock',
                NEW.venue_room_id, NEW.booking_date, new_start, new_end, overlapping, cap
                USING ERRCODE = 'exclusion_violation';
        END IF;
        RETURN NEW;
    END IF;

    -- Room-less booking: the binge-wide ceiling applies only to venues without
    -- bookable rooms (a single physical space). Venues WITH rooms are bounded
    -- by per-room assignment above; legacy room-less rows there are skipped.
    IF NEW.binge_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM venue_rooms vr
        WHERE vr.binge_id = NEW.binge_id AND vr.active AND vr.status = 'APPROVED'
    ) INTO has_rooms;
    IF has_rooms THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended('booking-venue-backstop:' || NEW.binge_id || ':' || NEW.booking_date, 0));

    SELECT GREATEST(COALESCE(bg.max_concurrent_bookings, 1), 1) INTO cap
    FROM binges bg WHERE bg.id = NEW.binge_id;
    IF cap IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COUNT(*) INTO overlapping
    FROM bookings b
    WHERE b.binge_id = NEW.binge_id
      AND b.venue_room_id IS NULL
      AND b.booking_date = NEW.booking_date
      AND b.id <> NEW.id
      AND b.status NOT IN ('CANCELLED', 'NO_SHOW')
      AND (EXTRACT(HOUR FROM b.start_time)::int * 60
           + EXTRACT(MINUTE FROM b.start_time)::int
           - COALESCE(b.setup_minutes, 0)) < new_end
      AND (EXTRACT(HOUR FROM b.start_time)::int * 60
           + EXTRACT(MINUTE FROM b.start_time)::int
           + CASE
               WHEN b.status = 'COMPLETED' AND b.actual_used_minutes IS NOT NULL
                   THEN ((b.actual_used_minutes + 29) / 30) * 30
               ELSE GREATEST(COALESCE(NULLIF(b.duration_minutes, 0), b.duration_hours * 60), 1)
             END
           + COALESCE(b.cleanup_minutes, 0)) > new_start;

    IF overlapping >= cap THEN
        RAISE EXCEPTION
            'VENUE_OCCUPANCY_BACKSTOP: binge % on % occupancy window [%,%) already has % overlapping active booking(s), ceiling % — a write path bypassed the booking slot lock',
            NEW.binge_id, NEW.booking_date, new_start, new_end, overlapping, cap
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END $$;

-- Recreate the trigger so buffer changes are also a re-check trigger column.
DROP TRIGGER IF EXISTS trg_booking_occupancy_backstop ON bookings;
CREATE TRIGGER trg_booking_occupancy_backstop
    AFTER INSERT OR UPDATE OF status, venue_room_id, booking_date, start_time,
                              duration_minutes, duration_hours,
                              setup_minutes, cleanup_minutes
    ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION booking_occupancy_backstop();

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: every match is `DROP CONSTRAINT IF EXISTS` or `DROP TRIGGER IF EXISTS`
-- paired with an immediate re-create, which is what makes this migration safely
-- re-runnable. No table, column or row is removed.
