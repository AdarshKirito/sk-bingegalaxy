-- V31: Persist cancellation actor on bookings so the freeze-policy
-- counters can distinguish customer-initiated cancellations from
-- system (payment-timeout) auto-cancels.  Without this column the two
-- threshold counters cross-bleed (every cancel matches both queries),
-- causing the wrong trigger type to be recorded and thresholds to fire
-- prematurely.
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS cancellation_actor VARCHAR(20);

-- Backfill: any historical CANCELLED bookings keep the column NULL so
-- they won't accidentally count toward future freezes.  Only new
-- cancellations recorded after this migration will populate the field.
CREATE INDEX IF NOT EXISTS idx_bookings_cancel_actor_window
    ON bookings (customer_id, binge_id, status, cancellation_actor, updated_at);
