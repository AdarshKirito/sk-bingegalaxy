\set ON_ERROR_STOP on
INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
VALUES (950,'V86 Venue',1,'IN','INR','Asia/Kolkata','APPROVED',TRUE) ON CONFLICT DO NOTHING;
INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order)
VALUES (950,950,'R','PRIVATE_ROOM',1,TRUE,'APPROVED',0) ON CONFLICT DO NOTHING;
INSERT INTO event_types (id,binge_id,name,base_price,hourly_rate,price_per_guest,min_hours,max_hours,active)
VALUES (950,950,'E',1000,500,0,1,8,TRUE) ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION t86(p_src TEXT, p_ref TEXT, p_start TIME) RETURNS VOID
LANGUAGE plpgsql AS $fn$
BEGIN
  INSERT INTO bookings (booking_ref,binge_id,venue_room_id,customer_id,customer_name,customer_email,
    customer_phone,event_type_id,booking_date,start_time,duration_hours,duration_minutes,
    setup_minutes,cleanup_minutes,number_of_guests,base_amount,add_on_amount,guest_amount,
    total_amount,subtotal_amount,venue_room_price,status,payment_status,origin,external_source,external_ref)
  VALUES ('C'||(EXTRACT(EPOCH FROM clock_timestamp())*1000000)::BIGINT%100000000,950,950,100,'G','g@e.com',
    '9999999999',950,DATE '2028-07-01',p_start,2,120,0,0,2,1000,0,0,1000,1000,0,'CONFIRMED','PENDING',
    'CHANNEL',p_src,p_ref);
END $fn$;

DO $$
BEGIN
  IF NOT (SELECT convalidated FROM pg_constraint WHERE conname='ck_booking_external_source_canonical') THEN
    RAISE EXCEPTION 'FAIL: V86 constraint left NOT VALID on a clean database';
  END IF;
  RAISE NOTICE 'PASS V86-1: canonical-source constraint present and validated';

  PERFORM t86('acme-channel','ACME-1',TIME '09:00');
  RAISE NOTICE 'PASS V86-2: canonical source accepted';

  BEGIN
    PERFORM t86('ACME-Channel','ACME-2',TIME '11:00');
    RAISE EXCEPTION 'FAIL: uppercase external_source was stored';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS V86-3: uppercase source rejected — retry guard cannot be defeated by casing';
  END;

  BEGIN
    PERFORM t86(' acme-channel','ACME-3',TIME '13:00');
    RAISE EXCEPTION 'FAIL: untrimmed external_source was stored';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS V86-4: untrimmed source rejected';
  END;

  -- case-sensitive refs must remain distinct
  PERFORM t86('acme-channel','acme-1',TIME '15:00');
  RAISE NOTICE 'PASS V86-5: refs differing only by case remain distinct bookings';
END $$;
DROP FUNCTION t86(TEXT,TEXT,TIME);
DO $$ BEGIN RAISE NOTICE '=== ALL V86 ASSERTIONS PASSED ==='; END $$;
