\set ON_ERROR_STOP on

-- Real schema, real migration chain. Seed a venue the way the app would.
INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
VALUES (901, 'Chain Test Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE)
ON CONFLICT (id) DO NOTHING;
INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order)
VALUES (901, 901, 'Exclusive', 'PRIVATE_ROOM', 1, TRUE, 'APPROVED', 0)
ON CONFLICT (id) DO NOTHING;
INSERT INTO event_types (id, binge_id, name, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
VALUES (901, 901, 'Celebration', 1000, 500, 0, 1, 8, TRUE)
ON CONFLICT (id) DO NOTHING;

-- 1) V82 made duration_minutes NOT NULL.
DO $$
DECLARE nullable TEXT;
BEGIN
    SELECT is_nullable INTO nullable FROM information_schema.columns
     WHERE table_name = 'bookings' AND column_name = 'duration_minutes';
    IF nullable <> 'NO' THEN RAISE EXCEPTION 'FAIL: duration_minutes is still nullable (%)', nullable; END IF;
    RAISE NOTICE 'PASS 1: duration_minutes is NOT NULL';
END $$;

-- 2) The duration CHECK validated cleanly on an empty/clean table.
DO $$
DECLARE validated BOOLEAN;
BEGIN
    SELECT convalidated INTO validated FROM pg_constraint WHERE conname = 'ck_booking_duration_minutes';
    IF validated IS NULL THEN RAISE EXCEPTION 'FAIL: ck_booking_duration_minutes missing'; END IF;
    IF NOT validated THEN RAISE EXCEPTION 'FAIL: constraint left NOT VALID on a clean database'; END IF;
    RAISE NOTICE 'PASS 2: ck_booking_duration_minutes present and validated';
END $$;

-- helper: insert a booking on the REAL schema
CREATE OR REPLACE FUNCTION t_book(p_room BIGINT, p_date DATE, p_start TIME,
                                  p_dur INT, p_setup INT, p_cleanup INT) RETURNS VOID
LANGUAGE plpgsql AS $fn$
BEGIN
    INSERT INTO bookings (booking_ref, binge_id, venue_room_id, customer_id, customer_name,
        customer_email, customer_phone, event_type_id, booking_date, start_time,
        duration_hours, duration_minutes, setup_minutes, cleanup_minutes, number_of_guests,
        base_amount, add_on_amount, guest_amount, total_amount, subtotal_amount,
        venue_room_price, status, payment_status)
    VALUES ('R' || (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::BIGINT % 100000000,
        901, p_room, 100, 'Guest', 'g@example.com', '9999999999', 901, p_date, p_start,
        p_dur / 60, p_dur, p_setup, p_cleanup, 2, 1000, 0, 0, 1000, 1000, 0, 'CONFIRMED', 'PENDING');
END $fn$;

-- 3) An odd duration is rejected by the V82 CHECK.
DO $$
BEGIN
    BEGIN
        PERFORM t_book(901, DATE '2027-05-01', TIME '10:00', 17, 0, 0);
        RAISE EXCEPTION 'FAIL: 17-minute duration accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 3: non-30-minute duration rejected by ck_booking_duration_minutes';
    END;
END $$;

-- 4) THE REGRESSION, on the real schema: cleanup buffer blocks back-to-back.
DO $$
BEGIN
    PERFORM t_book(901, DATE '2027-05-02', TIME '19:00', 180, 0, 45);
    BEGIN
        PERFORM t_book(901, DATE '2027-05-02', TIME '22:00', 180, 0, 45);
        RAISE EXCEPTION 'FAIL: back-to-back accepted despite a 45-minute cleanup buffer';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS 4: cleanup buffer blocks back-to-back on the real schema';
    END;
END $$;

-- 5) A start after the buffer clears is still accepted.
DO $$
BEGIN
    PERFORM t_book(901, DATE '2027-05-02', TIME '22:45', 60, 0, 45);
    RAISE NOTICE 'PASS 5: booking after the buffer clears is accepted';
END $$;

-- 6) Retro-fitting a buffer onto an existing booking is re-checked on UPDATE.
DO $$
BEGIN
    PERFORM t_book(901, DATE '2027-05-03', TIME '19:00', 120, 0, 0);
    PERFORM t_book(901, DATE '2027-05-03', TIME '21:00', 120, 0, 0);
    BEGIN
        UPDATE bookings SET cleanup_minutes = 30
         WHERE booking_date = DATE '2027-05-03' AND start_time = TIME '19:00';
        RAISE EXCEPTION 'FAIL: widening cleanup_minutes on UPDATE was not re-checked';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS 6: widening a buffer on UPDATE is re-checked by the trigger';
    END;
END $$;

-- 7) The V81 buffer CHECK constraints survived the rest of the chain.
DO $$
BEGIN
    BEGIN
        UPDATE binges SET default_cleanup_minutes = 999 WHERE id = 901;
        RAISE EXCEPTION 'FAIL: out-of-range venue buffer accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 7: venue buffer CHECK still enforced at head';
    END;
END $$;

-- 8) Entity/schema parity for every column the V81+V82 entities declare.
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(c.col, ', ') INTO missing FROM (VALUES
        ('bookings','setup_minutes'), ('bookings','cleanup_minutes'),
        ('slot_holds','setup_minutes'), ('slot_holds','cleanup_minutes'),
        ('binges','default_setup_minutes'), ('binges','default_cleanup_minutes'),
        ('event_types','setup_minutes'), ('event_types','cleanup_minutes')
    ) AS c(tbl, col)
    WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_name = c.tbl AND column_name = c.col);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'FAIL: ddl-auto=validate would fail — missing columns: %', missing;
    END IF;
    RAISE NOTICE 'PASS 8: every V81/V82 entity column exists (ddl-auto=validate safe)';
END $$;

DROP FUNCTION t_book(BIGINT, DATE, TIME, INT, INT, INT);
DO $$ BEGIN RAISE NOTICE '=== ALL V82 / FULL-CHAIN ASSERTIONS PASSED ==='; END $$;
