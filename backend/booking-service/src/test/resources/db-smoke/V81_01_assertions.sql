\set ON_ERROR_STOP on

-- 1) Backfill repaired the legacy rows the trigger would otherwise mis-count.
DO $$
DECLARE bad INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad FROM bookings WHERE duration_minutes IS NULL OR duration_minutes = 0;
    IF bad > 0 THEN RAISE EXCEPTION 'FAIL: % legacy rows still have unusable duration_minutes', bad; END IF;
    SELECT COUNT(*) INTO bad FROM bookings WHERE duration_minutes <> duration_hours * 60;
    IF bad > 0 THEN RAISE EXCEPTION 'FAIL: backfill did not match duration_hours*60'; END IF;
    RAISE NOTICE 'PASS 1: legacy duration_minutes backfilled';
END $$;

-- 2) CHECK constraints reject out-of-range buffers.
DO $$
BEGIN
    BEGIN
        UPDATE binges SET default_cleanup_minutes = 999 WHERE id = 1;
        RAISE EXCEPTION 'FAIL: 999-minute buffer was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 2: out-of-range buffer rejected';
    END;
END $$;

-- 3) THE REGRESSION. Room 1 (capacity 1): 19:00-22:00 with a 45-minute cleanup
--    must block a 22:00 start that the pre-V81 trigger allowed.
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                      duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
VALUES (1, 1, DATE '2026-09-10', TIME '19:00', 3, 180, 0, 45, 'CONFIRMED');

DO $$
BEGIN
    BEGIN
        INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                              duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
        VALUES (1, 1, DATE '2026-09-10', TIME '22:00', 3, 180, 0, 45, 'CONFIRMED');
        RAISE EXCEPTION 'FAIL: back-to-back booking accepted despite a 45-minute cleanup buffer';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS 3: back-to-back booking blocked by the cleanup buffer';
    END;
END $$;

-- 4) A start AFTER the buffer clears is still accepted — the buffer must not
--    become an unconditional lockout.
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                      duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
VALUES (1, 1, DATE '2026-09-10', TIME '22:45', 1, 60, 0, 45, 'CONFIRMED');
DO $$ BEGIN RAISE NOTICE 'PASS 4: booking after the buffer clears is accepted'; END $$;

-- 5) Setup buffer on the LATER booking also creates the conflict (both sides widen).
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                      duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
VALUES (1, 1, DATE '2026-09-11', TIME '19:00', 3, 180, 0, 0, 'CONFIRMED');
DO $$
BEGIN
    BEGIN
        INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                              duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
        VALUES (1, 1, DATE '2026-09-11', TIME '22:00', 2, 120, 30, 0, 'CONFIRMED');
        RAISE EXCEPTION 'FAIL: later booking''s setup buffer did not create a conflict';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS 5: setup buffer on the later booking blocks the overlap';
    END;
END $$;

-- 6) Zero buffers reproduce the exact pre-V81 behaviour: back-to-back is legal.
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                      duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
VALUES (1, 1, DATE '2026-09-12', TIME '19:00', 3, 180, 0, 0, 'CONFIRMED'),
       (1, 1, DATE '2026-09-12', TIME '22:00', 3, 180, 0, 0, 'CONFIRMED');
DO $$ BEGIN RAISE NOTICE 'PASS 6: zero-buffer venues keep pre-V81 back-to-back behaviour'; END $$;

-- 7) Room capacity 2 tolerates one buffered overlap but not two.
INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                      duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
VALUES (1, 2, DATE '2026-09-13', TIME '19:00', 2, 120, 0, 60, 'CONFIRMED'),
       (1, 2, DATE '2026-09-13', TIME '20:00', 2, 120, 0, 60, 'CONFIRMED');
DO $$
BEGIN
    BEGIN
        INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                              duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
        VALUES (1, 2, DATE '2026-09-13', TIME '20:30', 2, 120, 0, 60, 'CONFIRMED');
        RAISE EXCEPTION 'FAIL: third overlapping booking exceeded room capacity 2';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS 7: capacity ceiling still enforced with buffers applied';
    END;
END $$;

-- 8) A status-only transition on an at-capacity row must NOT be re-rejected.
DO $$
DECLARE target BIGINT;
BEGIN
    SELECT id INTO target FROM bookings
     WHERE booking_date = DATE '2026-09-13' AND start_time = TIME '19:00';
    UPDATE bookings SET status = 'CHECKED_IN' WHERE id = target;
    RAISE NOTICE 'PASS 8: status-only transition not blocked by its own occupancy';
END $$;

-- 9) Cancelling frees the window.
DO $$
DECLARE target BIGINT;
BEGIN
    SELECT id INTO target FROM bookings
     WHERE booking_date = DATE '2026-09-10' AND start_time = TIME '19:00';
    UPDATE bookings SET status = 'CANCELLED' WHERE id = target;
    INSERT INTO bookings (binge_id, venue_room_id, booking_date, start_time,
                          duration_hours, duration_minutes, setup_minutes, cleanup_minutes, status)
    VALUES (1, 1, DATE '2026-09-10', TIME '19:00', 3, 180, 0, 45, 'CONFIRMED');
    RAISE NOTICE 'PASS 9: cancelled booking releases its buffered window';
END $$;

DO $$ BEGIN RAISE NOTICE '=== ALL V81 TRIGGER ASSERTIONS PASSED ==='; END $$;
