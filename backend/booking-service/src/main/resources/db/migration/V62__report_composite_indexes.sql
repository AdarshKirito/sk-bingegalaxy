-- V62: Composite indexes for report aggregation queries.
--
-- Without these, the admin revenue report, booking-by-date dashboard, and
-- event-type breakdown all force a full sequential scan of the bookings table.
-- Under production data volume (10k+ bookings/binge) this causes p95 latency
-- to spike from ~5ms to 2–10s on every admin page load.
--
-- NOTE: CONCURRENTLY is intentionally omitted here. Flyway wraps migrations in
-- a transaction by default, and PostgreSQL does not allow CREATE INDEX CONCURRENTLY
-- inside a transaction. The brief table lock during index creation is acceptable
-- for a maintenance deployment. Run these manually with CONCURRENTLY in production
-- during a live cutover if zero-downtime index build is required.
--
-- Each index covers a distinct report dimension:

-- ── Bookings table ────────────────────────────────────────────────────────

-- Revenue report: binge_id + date range + status (most common admin filter combo)
CREATE INDEX IF NOT EXISTS idx_bookings_binge_date_status
    ON bookings (binge_id, booking_date, status);

-- Event-type revenue breakdown per binge (SELECT event_type_id, SUM(total_amount))
CREATE INDEX IF NOT EXISTS idx_bookings_binge_event_status
    ON bookings (binge_id, event_type_id, status);

-- Financial reconciliation: bookings confirmed with outstanding payment
CREATE INDEX IF NOT EXISTS idx_bookings_binge_status_payment
    ON bookings (binge_id, status, payment_status);

-- Customer booking history (customer portal "My Bookings")
CREATE INDEX IF NOT EXISTS idx_bookings_customer_date
    ON bookings (customer_id, booking_date);

-- Global admin date-range dashboard (cross-binge)
CREATE INDEX IF NOT EXISTS idx_bookings_date_status
    ON bookings (booking_date, status);

-- ── Booking read model (CQRS projection) ─────────────────────────────────

-- Cross-status dashboard sorted by date
CREATE INDEX IF NOT EXISTS idx_brm_status_date
    ON booking_read_model (status, booking_date);

-- ── Booking event log (timeline / audit trail) ────────────────────────────

-- Timeline query: all events for a booking, filtered by event_type
-- (booking_event_log uses booking_ref, not booking_id — no FK, intentional
-- for append-only audit log decoupled from the bookings table lifecycle)
CREATE INDEX IF NOT EXISTS idx_bel_booking_ref_event_type
    ON booking_event_log (booking_ref, event_type);

-- ── Outbox event (poller hot path) ───────────────────────────────────────
-- Partial index covering the SKIP LOCKED query: sent=false + failed=false
-- filtered at the index level — eliminates sort on the full table.
CREATE INDEX IF NOT EXISTS idx_outbox_pending_created
    ON outbox_event (created_at)
    WHERE sent = false AND failed_permanent = false;
