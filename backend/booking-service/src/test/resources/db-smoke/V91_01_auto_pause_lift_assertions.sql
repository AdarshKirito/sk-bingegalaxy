\set ON_ERROR_STOP on
-- V91: an auto-pause must end when the thing it was punishing ends — and a MANUAL
-- pause must survive untouched.
--
-- The trigger is the database's half of the guarantee. The application lifts the pause
-- on every path it owns; this covers the paths it does not, DataSeeder being the one
-- that caused the original V87 incident by inserting event types directly.

INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active)
VALUES (960,'V91 Auto-paused',1,'IN','INR','Asia/Kolkata','APPROVED',FALSE),
       (961,'V91 Manually paused',1,'IN','INR','Asia/Kolkata','APPROVED',FALSE),
       (962,'V91 Healthy',1,'IN','INR','Asia/Kolkata','APPROVED',TRUE) ON CONFLICT DO NOTHING;

-- The distinguishing state. Only the grace sweep writes auto_deactivated_at.
UPDATE binges SET auto_deactivated_at = NOW() - INTERVAL '2 days',
                  grace_warning_sent_at = NOW() - INTERVAL '3 days',
                  first_event_created_at = NULL
 WHERE id = 960;
UPDATE binges SET auto_deactivated_at = NULL,          -- a human paused this one
                  first_event_created_at = NULL
 WHERE id = 961;

DO $$
BEGIN
  -- ── The venue the sweep paused ──────────────────────────────────────────
  INSERT INTO event_types (id,binge_id,name,base_price,hourly_rate,price_per_guest,
                           min_hours,max_hours,active)
  VALUES (960,960,'Whole venue',1000,500,0,1,8,TRUE);

  IF NOT (SELECT active FROM binges WHERE id = 960) THEN
    RAISE EXCEPTION 'FAIL: an auto-paused venue is still paused after gaining an event type';
  END IF;
  RAISE NOTICE 'PASS V91-1: adding an event type lifted the automatic pause';

  IF (SELECT auto_deactivated_at FROM binges WHERE id = 960) IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: the auto-pause marker survived the lift';
  END IF;
  RAISE NOTICE 'PASS V91-2: the auto-pause marker was cleared';

  -- Cleared so a venue that goes through the cycle again is warned again rather than
  -- jumping straight to a second pause.
  IF (SELECT grace_warning_sent_at FROM binges WHERE id = 960) IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: the grace warning stamp survived, so a relapse would be silent';
  END IF;
  RAISE NOTICE 'PASS V91-3: the grace-warning stamp was cleared';

  -- V87's behaviour must survive V91's rewrite of the same function.
  IF (SELECT first_event_created_at FROM binges WHERE id = 960) IS NULL THEN
    RAISE EXCEPTION 'FAIL: V87 stamping regressed — the venue has events and a NULL flag';
  END IF;
  RAISE NOTICE 'PASS V91-4: first_event_created_at is still stamped (V87 preserved)';

  -- ── The venue a human paused ────────────────────────────────────────────
  INSERT INTO event_types (id,binge_id,name,base_price,hourly_rate,price_per_guest,
                           min_hours,max_hours,active)
  VALUES (961,961,'Whole venue',1000,500,0,1,8,TRUE);

  IF (SELECT active FROM binges WHERE id = 961) THEN
    RAISE EXCEPTION 'FAIL: a MANUALLY paused venue was put back on sale by an event type';
  END IF;
  -- Resurrecting a venue someone deliberately took off sale is a worse failure than
  -- the one V91 repairs, which is why every statement is predicated on the marker.
  RAISE NOTICE 'PASS V91-5: a manual pause is never lifted';

  IF (SELECT first_event_created_at FROM binges WHERE id = 961) IS NULL THEN
    RAISE EXCEPTION 'FAIL: the manually paused venue was not stamped operational';
  END IF;
  RAISE NOTICE 'PASS V91-6: it is still stamped operational — paused is not the same as unset up';

  -- ── A healthy venue is not disturbed ────────────────────────────────────
  INSERT INTO event_types (id,binge_id,name,base_price,hourly_rate,price_per_guest,
                           min_hours,max_hours,active)
  VALUES (962,962,'Whole venue',1000,500,0,1,8,TRUE);

  IF NOT (SELECT active FROM binges WHERE id = 962) THEN
    RAISE EXCEPTION 'FAIL: an active venue was disturbed by the trigger';
  END IF;
  RAISE NOTICE 'PASS V91-7: an active venue is left alone';
END $$;
DO $$ BEGIN RAISE NOTICE '=== ALL V91 ASSERTIONS PASSED ==='; END $$;
