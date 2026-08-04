-- DATA-001: database backstop against physical double-booking.
--
-- Double-booking prevention lives in application code: a pg_advisory_xact_lock
-- on (binge, date) plus post-lock conflict/capacity checks in BookingService.
-- That guard is confirmed working, but it is the ONLY guard — any future write
-- path that forgets acquireSlotLock (or a multi-primary topology) could insert
-- overlapping bookings with no schema-level defence.
--
-- Rooms may have capacity > 1 and conflicts are overlap-window based, so a
-- simple UNIQUE/EXCLUDE cannot express the rule. This trigger mirrors the
-- application's occupancy semantics exactly:
--   * active = status NOT IN ('CANCELLED','NO_SHOW')
--   * effective duration = actual_used_minutes rounded UP to 30 for COMPLETED
--     rows (0 = vacated), else duration_minutes (fallback duration_hours*60)
--   * room-assigned: overlapping active bookings in the room must stay < capacity
--   * room-less booking at a room-less venue: < max_concurrent_bookings (default 1)
--
-- Concurrency: the trigger serialises per room+date (or binge+date) through its
-- own advisory-lock keyspace, so even two writers that both skipped the
-- application lock cannot both pass the count — the second blocks until the
-- first commits and then sees its row.

CREATE INDEX IF NOT EXISTS idx_booking_room_date
    ON bookings (venue_room_id, booking_date)
    WHERE venue_room_id IS NOT NULL;

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
    -- exactly at capacity including this booking.
    IF TG_OP = 'UPDATE'
       AND OLD.status NOT IN ('CANCELLED', 'NO_SHOW')
       AND OLD.venue_room_id IS NOT DISTINCT FROM NEW.venue_room_id
       AND OLD.booking_date = NEW.booking_date
       AND OLD.start_time = NEW.start_time
       AND OLD.duration_minutes IS NOT DISTINCT FROM NEW.duration_minutes
       AND OLD.duration_hours = NEW.duration_hours THEN
        RETURN NEW;
    END IF;

    new_start := EXTRACT(HOUR FROM NEW.start_time)::int * 60
               + EXTRACT(MINUTE FROM NEW.start_time)::int;
    new_end := new_start
             + GREATEST(COALESCE(NULLIF(NEW.duration_minutes, 0), NEW.duration_hours * 60), 1);

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
          AND (EXTRACT(HOUR FROM b.start_time)::int * 60
               + EXTRACT(MINUTE FROM b.start_time)::int) < new_end
          AND (EXTRACT(HOUR FROM b.start_time)::int * 60
               + EXTRACT(MINUTE FROM b.start_time)::int
               + CASE
                   WHEN b.status = 'COMPLETED' AND b.actual_used_minutes IS NOT NULL
                       THEN ((b.actual_used_minutes + 29) / 30) * 30
                   ELSE GREATEST(COALESCE(NULLIF(b.duration_minutes, 0), b.duration_hours * 60), 1)
                 END) > new_start;

        IF overlapping >= cap THEN
            RAISE EXCEPTION
                'ROOM_OCCUPANCY_BACKSTOP: room % on % window [%,%) already has % overlapping active booking(s), capacity % — a write path bypassed the booking slot lock',
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
           + EXTRACT(MINUTE FROM b.start_time)::int) < new_end
      AND (EXTRACT(HOUR FROM b.start_time)::int * 60
           + EXTRACT(MINUTE FROM b.start_time)::int
           + CASE
               WHEN b.status = 'COMPLETED' AND b.actual_used_minutes IS NOT NULL
                   THEN ((b.actual_used_minutes + 29) / 30) * 30
               ELSE GREATEST(COALESCE(NULLIF(b.duration_minutes, 0), b.duration_hours * 60), 1)
             END) > new_start;

    IF overlapping >= cap THEN
        RAISE EXCEPTION
            'VENUE_OCCUPANCY_BACKSTOP: binge % on % window [%,%) already has % overlapping active booking(s), ceiling % — a write path bypassed the booking slot lock',
            NEW.binge_id, NEW.booking_date, new_start, new_end, overlapping, cap
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_booking_occupancy_backstop ON bookings;
CREATE TRIGGER trg_booking_occupancy_backstop
    AFTER INSERT OR UPDATE OF status, venue_room_id, booking_date, start_time,
                              duration_minutes, duration_hours
    ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION booking_occupancy_backstop();

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: the only match is `DROP TRIGGER IF EXISTS trg_booking_occupancy_backstop`,
-- immediately followed by CREATE TRIGGER in the same migration. It replaces a trigger
-- so the migration is re-runnable; no rows are touched.
