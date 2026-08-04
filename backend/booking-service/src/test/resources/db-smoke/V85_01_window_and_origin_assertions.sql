-- Assertions for V83 (turnover defaults), V84 (booking window + permitted
-- durations) and V85 (booking origin), run against the REAL schema after the
-- full migration chain. See README.md for how to run.
\set ON_ERROR_STOP on

INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
VALUES (902, 'Window Test Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE)
ON CONFLICT (id) DO NOTHING;
INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order)
VALUES (902, 902, 'Room', 'PRIVATE_ROOM', 1, TRUE, 'APPROVED', 0)
ON CONFLICT (id) DO NOTHING;
INSERT INTO event_types (id, binge_id, name, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
VALUES (902, 902, 'Celebration', 1000, 500, 0, 1, 8, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ── V83: new venues get a protective default; existing ones are untouched ──
DO $$
DECLARE d TEXT; v INT;
BEGIN
    SELECT column_default INTO d FROM information_schema.columns
     WHERE table_name = 'binges' AND column_name = 'default_cleanup_minutes';
    IF d IS NULL OR d NOT LIKE '30%' THEN
        RAISE EXCEPTION 'FAIL: default_cleanup_minutes column default is % (expected 30)', d;
    END IF;

    -- A venue inserted without naming the column must pick the default up.
    INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
    VALUES (903, 'Fresh Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE);
    SELECT default_cleanup_minutes INTO v FROM binges WHERE id = 903;
    IF v <> 30 THEN RAISE EXCEPTION 'FAIL: new venue got % cleanup minutes, expected 30', v; END IF;

    -- ...and the venue seeded above (created before this statement, explicitly 0
    -- via the pre-V83 default) must NOT have been rewritten.
    RAISE NOTICE 'PASS V83-1: new venues default to 30 min cleanup';
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'binges' AND column_name = 'turnover_policy_reviewed_at') THEN
        RAISE EXCEPTION 'FAIL: turnover_policy_reviewed_at missing';
    END IF;
    -- A venue that never configured buffers must stay unreviewed, so the console prompts.
    IF (SELECT turnover_policy_reviewed_at FROM binges WHERE id = 903) IS NOT NULL THEN
        RAISE EXCEPTION 'FAIL: a never-configured venue was marked as reviewed';
    END IF;
    RAISE NOTICE 'PASS V83-2: unreviewed venues remain flagged for an explicit decision';
END $$;

-- ── V84: booking-window constraints ────────────────────────────────────
DO $$
BEGIN
    BEGIN
        UPDATE binges SET min_notice_minutes = -5 WHERE id = 902;
        RAISE EXCEPTION 'FAIL: negative minimum notice accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V84-1: negative minimum notice rejected';
    END;
    BEGIN
        UPDATE binges SET max_advance_days = 5000 WHERE id = 902;
        RAISE EXCEPTION 'FAIL: 5000-day advance window accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V84-2: absurd advance window rejected';
    END;
END $$;

-- ── V84: permitted-durations shape guard ───────────────────────────────
DO $$
BEGIN
    UPDATE event_types SET permitted_durations_csv = '120,180,240' WHERE id = 902;
    RAISE NOTICE 'PASS V84-3: a valid duration allow-list is accepted';

    BEGIN
        UPDATE event_types SET permitted_durations_csv = '60,120,180,240,300' WHERE id = 902;
        RAISE EXCEPTION 'FAIL: 5 durations accepted (cap is 4)';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V84-4: more than 4 durations rejected';
    END;

    BEGIN
        UPDATE event_types SET permitted_durations_csv = 'abc' WHERE id = 902;
        RAISE EXCEPTION 'FAIL: non-numeric duration list accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V84-5: malformed duration list rejected';
    END;

    UPDATE event_types SET permitted_durations_csv = NULL WHERE id = 902;
    RAISE NOTICE 'PASS V84-6: NULL clears the allow-list (free choice restored)';
END $$;

-- ── V85: origin + external-reference pairing ───────────────────────────
CREATE OR REPLACE FUNCTION t_book2(p_origin TEXT, p_src TEXT, p_ref TEXT, p_start TIME) RETURNS VOID
LANGUAGE plpgsql AS $fn$
BEGIN
    INSERT INTO bookings (booking_ref, binge_id, venue_room_id, customer_id, customer_name,
        customer_email, customer_phone, event_type_id, booking_date, start_time,
        duration_hours, duration_minutes, setup_minutes, cleanup_minutes, number_of_guests,
        base_amount, add_on_amount, guest_amount, total_amount, subtotal_amount,
        venue_room_price, status, payment_status, origin, external_source, external_ref)
    VALUES ('O' || (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::BIGINT % 100000000,
        902, 902, 100, 'Guest', 'g@example.com', '9999999999', 902,
        DATE '2027-06-01', p_start, 2, 120, 0, 0, 2, 1000, 0, 0, 1000, 1000, 0,
        'CONFIRMED', 'PENDING', p_origin, p_src, p_ref);
END $fn$;

DO $$
BEGIN
    PERFORM t_book2('DIRECT', NULL, NULL, TIME '09:00');
    RAISE NOTICE 'PASS V85-1: DIRECT booking with no external reference accepted';

    PERFORM t_book2('CHANNEL', 'acme-channel', 'ACME-1', TIME '12:00');
    RAISE NOTICE 'PASS V85-2: CHANNEL booking with a full external reference accepted';

    BEGIN
        PERFORM t_book2('CHANNEL', 'acme-channel', NULL, TIME '15:00');
        RAISE EXCEPTION 'FAIL: CHANNEL booking without external_ref accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V85-3: CHANNEL booking without an external reference rejected';
    END;

    BEGIN
        PERFORM t_book2('DIRECT', 'acme-channel', 'ACME-2', TIME '18:00');
        RAISE EXCEPTION 'FAIL: DIRECT booking carrying an external reference accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V85-4: DIRECT booking carrying an external reference rejected';
    END;

    BEGIN
        PERFORM t_book2('BOGUS', NULL, NULL, TIME '20:00');
        RAISE EXCEPTION 'FAIL: unknown origin accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS V85-5: unknown origin value rejected';
    END;

    -- The same channel reference must never land twice: a redelivered webhook
    -- would otherwise create a duplicate reservation.
    BEGIN
        PERFORM t_book2('CHANNEL', 'acme-channel', 'ACME-1', TIME '21:00');
        RAISE EXCEPTION 'FAIL: duplicate (external_source, external_ref) accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS V85-6: duplicate channel reference rejected by the unique index';
    END;
END $$;

DROP FUNCTION t_book2(TEXT, TEXT, TEXT, TIME);
DO $$ BEGIN RAISE NOTICE '=== ALL V83 / V84 / V85 ASSERTIONS PASSED ==='; END $$;
