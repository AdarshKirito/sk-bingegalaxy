\set ON_ERROR_STOP on
-- V90: a channel reservation's identity is (VENUE, source, reference).
--
-- V85 made (external_source, external_ref) globally unique. external_source is the
-- destination slug every venue on that destination shares, and external_ref is chosen
-- by the RESELLER — so two venues lived in one flat namespace keyed by a value neither
-- of them controls. Venue B's reservation carrying a reference venue A had used was
-- answered "already recorded" with venue A's booking, and a cancellation resolved to
-- whichever venue got there first.
--
-- These assertions prove both halves: the cross-venue collision is gone, and the
-- redelivery guard V85 existed for is untouched.

INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
VALUES (990,'V90 Venue A',1,'IN','INR','Asia/Kolkata','APPROVED',TRUE),
       (991,'V90 Venue B',2,'IN','INR','Asia/Kolkata','APPROVED',TRUE) ON CONFLICT DO NOTHING;
INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order)
VALUES (990,990,'R','PRIVATE_ROOM',1,TRUE,'APPROVED',0),
       (991,991,'R','PRIVATE_ROOM',1,TRUE,'APPROVED',0) ON CONFLICT DO NOTHING;
INSERT INTO event_types (id,binge_id,name,base_price,hourly_rate,price_per_guest,min_hours,max_hours,active)
VALUES (990,990,'E',1000,500,0,1,8,TRUE),
       (991,991,'E',1000,500,0,1,8,TRUE) ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION t90(p_binge BIGINT, p_src TEXT, p_ref TEXT, p_start TIME) RETURNS VOID
LANGUAGE plpgsql AS $fn$
BEGIN
  INSERT INTO bookings (booking_ref,binge_id,venue_room_id,customer_id,customer_name,customer_email,
    customer_phone,event_type_id,booking_date,start_time,duration_hours,duration_minutes,
    setup_minutes,cleanup_minutes,number_of_guests,base_amount,add_on_amount,guest_amount,
    total_amount,subtotal_amount,venue_room_price,status,payment_status,origin,external_source,external_ref)
  VALUES ('D'||(EXTRACT(EPOCH FROM clock_timestamp())*1000000)::BIGINT%100000000,p_binge,p_binge,100,'G','g@e.com',
    '9999999999',p_binge,DATE '2028-08-01',p_start,2,120,0,0,2,1000,0,0,1000,1000,0,'CONFIRMED','PENDING',
    'CHANNEL',p_src,p_ref);
END $fn$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uk_booking_external_ref') THEN
    RAISE EXCEPTION 'FAIL: the global (source, ref) index still exists — venues still share one namespace';
  END IF;
  RAISE NOTICE 'PASS V90-1: the global (external_source, external_ref) index is gone';

  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uk_booking_external_ref_venue') THEN
    RAISE EXCEPTION 'FAIL: the venue-scoped uniqueness index was not created';
  END IF;
  RAISE NOTICE 'PASS V90-2: uk_booking_external_ref_venue present';

  -- THE BUG. Same destination, same reseller-chosen reference, two different venues.
  PERFORM t90(990,'simulator','OCTO-1',TIME '09:00');
  PERFORM t90(991,'simulator','OCTO-1',TIME '09:00');
  RAISE NOTICE 'PASS V90-3: two venues may hold the same channel reference — no cross-venue collision';

  IF (SELECT COUNT(DISTINCT binge_id) FROM bookings
       WHERE external_source='simulator' AND external_ref='OCTO-1') <> 2 THEN
    RAISE EXCEPTION 'FAIL: the two venues did not both get their own booking';
  END IF;
  RAISE NOTICE 'PASS V90-4: each venue owns its own row for that reference';

  -- And the guarantee V85 existed for is untouched: within ONE venue, a redelivered
  -- reservation must still collide rather than double-book the slot.
  BEGIN
    PERFORM t90(990,'simulator','OCTO-1',TIME '15:00');
    RAISE EXCEPTION 'FAIL: a redelivered reservation created a SECOND booking for the same venue';
  EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE 'PASS V90-5: a redelivery within one venue still collides — the guard is intact';
  END;

  -- The V85 pairing CHECK must survive: a CHANNEL row still needs both columns.
  BEGIN
    PERFORM t90(990,'simulator',NULL,TIME '17:00');
    RAISE EXCEPTION 'FAIL: a CHANNEL booking without an external_ref was accepted';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS V90-6: the origin/external-ref pairing CHECK still holds';
  END;
END $$;
DROP FUNCTION t90(BIGINT,TEXT,TEXT,TIME);
DO $$ BEGIN RAISE NOTICE '=== ALL V90 ASSERTIONS PASSED ==='; END $$;
