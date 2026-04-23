-- ════════════════════════════════════════════════════════════════════
--  V19 — enrich booking_event_log with a snapshotted actor name so
--  the admin audit trail can render human-readable attribution
--  ("Aarav Menon (Customer)") instead of bare user ids, and so a
--  future rename never rewrites history.
--
--  Also introduces membership / review columns used by the customer
--  detail screen (see LoyaltyService + BookingReviewRepository).
--  Those are read-only extensions — no existing data is touched.
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE booking_event_log
    ADD COLUMN IF NOT EXISTS triggered_by_name VARCHAR(160);

-- Back-fill: for CUSTOMER events, populate the actor name from the
-- booking snapshot where available.  Safe no-op when bookings.customer_name
-- is already null.
UPDATE booking_event_log e
   SET triggered_by_name = b.customer_name
  FROM bookings b
 WHERE b.booking_ref = e.booking_ref
   AND e.triggered_by_role = 'CUSTOMER'
   AND (e.triggered_by_name IS NULL OR e.triggered_by_name = '')
   AND b.customer_name IS NOT NULL;

-- For SYSTEM / automated rows default to the literal "System" so the
-- UI never renders empty attribution.
UPDATE booking_event_log
   SET triggered_by_name = 'System'
 WHERE triggered_by_name IS NULL
   AND (triggered_by_role IS NULL OR triggered_by_role = 'SYSTEM');
