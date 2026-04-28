-- V26: Per-binge operating hours.
--
-- Booking-service previously had no awareness of opening/closing hours.
-- Only availability-service used the global THEATER_OPENING_HOUR /
-- THEATER_CLOSING_HOUR env vars (default 10:00 / 23:00) to render the slot
-- grid, but a direct POST /api/v1/bookings would happily accept a booking at
-- 03:00. This migration introduces per-binge override columns; booking-service
-- now validates startTime + duration falls within [open_time, close_time].
-- When NULL, booking-service falls back to the global @Value defaults.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS open_time  TIME,
    ADD COLUMN IF NOT EXISTS close_time TIME;

-- Backfill existing rows with the historical global defaults so behaviour is
-- consistent with what the customer-facing slot grid was already showing.
UPDATE binges
   SET open_time  = '10:00:00'
 WHERE open_time IS NULL;

UPDATE binges
   SET close_time = '23:00:00'
 WHERE close_time IS NULL;

-- Sanity guard: close_time must be strictly after open_time. We enforce this
-- in application code (BingeService) for friendlier error messages, but a DB
-- check is defence in depth against direct SQL writes.
ALTER TABLE binges
    ADD CONSTRAINT chk_binge_hours_order
    CHECK (close_time IS NULL OR open_time IS NULL OR close_time > open_time);
