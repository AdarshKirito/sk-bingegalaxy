-- Per-day operating hours for a binge, stored as a JSON array of up to 7 entries:
--   [{"dayOfWeek":1,"closed":false,"openTime":"10:00","closeTime":"23:00"}, ...]
-- where dayOfWeek follows java.time.DayOfWeek (1=Monday ... 7=Sunday).
--
-- This is an OVERRIDE layer on top of the existing single open_time/close_time pair,
-- which remains the fallback/default. Booking validation resolves the specific day's
-- hours when this column is populated (rejecting bookings on days marked closed or
-- outside that day's window); when it is NULL/empty the binge behaves exactly as
-- before. Existing rows are left NULL, so no behavioural change on migrate.
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS opening_hours_json TEXT;
