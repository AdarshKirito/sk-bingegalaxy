-- V82: make duration_minutes the canonical, non-nullable duration (gap G9).
--
-- duration_hours is a LOSSY legacy column: every write path stores it as an
-- integer-truncated durationMinutes/60, so a 90-minute booking persists 1. Any
-- code that falls back to duration_hours * 60 therefore reads 60 for a 90-minute
-- reservation.
--
-- That fallback was reimplemented in SIX places (booking service, check-in,
-- no-show sweep, invoice PDF, pricing, CSV export) and had already drifted:
-- five guarded `duration_minutes > 0`, the CSV export did not — so a zero row
-- exported as 0 minutes while every other subsystem read duration_hours * 60.
-- Java now routes everything through Booking#getScheduledDurationMinutes(); this
-- migration removes the schema's ability to produce the ambiguous state at all.
--
-- Safe to apply because V81 already backfilled every NULL/0 row. The backfill is
-- repeated here so V82 is independently correct if the two are ever applied out
-- of order or replayed against a partially-migrated database.

UPDATE bookings
   SET duration_minutes = GREATEST(duration_hours, 1) * 60
 WHERE duration_minutes IS NULL
    OR duration_minutes <= 0;

ALTER TABLE bookings
    ALTER COLUMN duration_minutes SET NOT NULL;

-- Mirror the application's own validation (BookingService: 30..720 in 30-minute
-- steps). A CHECK rather than a trigger: it is cheap, declarative, and makes the
-- rule visible to anyone reading the schema.
--
-- NOT VALID deliberately: it enforces the rule on every future INSERT/UPDATE
-- without taking the full-table ACCESS EXCLUSIVE scan that validation requires.
-- Legacy rows that predate the 30-minute rule keep working; the separate
-- VALIDATE below upgrades the constraint under a weaker lock.
ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS ck_booking_duration_minutes;
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_duration_minutes
        CHECK (duration_minutes BETWEEN 30 AND 720 AND duration_minutes % 30 = 0)
        NOT VALID;

-- Validate only if the existing data already satisfies it. A venue that legitimately
-- has odd-length legacy bookings must not have its deployment blocked by a
-- constraint that only ever needed to govern new writes.
DO $$
DECLARE offending INTEGER;
BEGIN
    SELECT COUNT(*) INTO offending
      FROM bookings
     WHERE duration_minutes < 30 OR duration_minutes > 720 OR duration_minutes % 30 <> 0;

    IF offending = 0 THEN
        ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_duration_minutes;
        RAISE NOTICE 'V82: ck_booking_duration_minutes validated against all existing rows';
    ELSE
        RAISE WARNING
            'V82: % booking(s) have a duration outside 30..720 or not a 30-minute multiple. '
            'The constraint is active for new writes but left NOT VALID so the deploy proceeds. '
            'Reconcile those rows, then run: ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_duration_minutes;',
            offending;
    END IF;
END $$;

COMMENT ON COLUMN bookings.duration_minutes IS
    'Canonical scheduled duration. Read via Booking#getScheduledDurationMinutes() — never compute from duration_hours.';
COMMENT ON COLUMN bookings.duration_hours IS
    'DEPRECATED (V82). Lossy: stored as an integer-truncated duration_minutes/60, so 90 minutes persists as 1. Retained only for the BookingEvent wire contract consumed by notification-service.';

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: `DROP CONSTRAINT IF EXISTS ck_booking_duration_minutes` followed by
-- ADD CONSTRAINT. Note what this migration deliberately does NOT do: duration_hours is
-- marked deprecated in a COMMENT and left in place, because notification-service still
-- reads it off the wire. Dropping it is a later, separate step.
