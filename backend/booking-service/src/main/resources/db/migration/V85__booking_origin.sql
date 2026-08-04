-- V85: where a reservation came from (gap G8), and the hook that lets anti-abuse
-- rules depend on it (gap G3).
--
-- Every booking today is implicitly a direct customer booking. That assumption is
-- baked into the anti-abuse guards in BookingService: unpaid-booking limits,
-- pending-duplicate detection, customer freezes and risk flags all exist to stop a
-- *customer* misusing the funnel.
--
-- Applied to a reservation that arrived, already paid, from a sales channel, those
-- same guards are actively wrong. A channel guest has no SK account and no funnel
-- to abuse; rejecting their booking because a synthetic customer id "already has 2
-- unpaid bookings" loses real, paid business and is invisible until a venue asks
-- why a channel reservation never appeared.
--
-- This migration deliberately adds ONLY the discriminator and the external
-- references. The enforcement change lives in Java (BookingOriginPolicy) so the
-- rule is reviewable and testable rather than encoded in SQL.
--
-- Provider-neutral by design: no channel names, no OTA vocabulary. Booking-service
-- must never learn what "Bókun" is — that belongs to the future distribution
-- bounded context. `external_source` is a free-form slug the caller supplies.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS origin          VARCHAR(16)  NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN IF NOT EXISTS external_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS external_ref    VARCHAR(128);

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS ck_booking_origin;
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_origin
        CHECK (origin IN ('DIRECT', 'ADMIN', 'CHANNEL'));

-- An external reference is only meaningful for a CHANNEL booking, and a CHANNEL
-- booking without one cannot be reconciled or matched to an inbound cancellation.
-- Enforcing the pairing in the schema means a half-populated row is impossible.
ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS ck_booking_external_ref_pairing;
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_external_ref_pairing
        CHECK (
            (origin = 'CHANNEL' AND external_source IS NOT NULL AND external_ref IS NOT NULL)
            OR (origin <> 'CHANNEL' AND external_source IS NULL AND external_ref IS NULL)
        )
        -- NOT VALID: no CHANNEL rows can exist yet, so this only ever governs future
        -- writes. Keeping it NOT VALID avoids an ACCESS EXCLUSIVE full-table scan on
        -- a large bookings table during deploy.
        NOT VALID;

DO $$
DECLARE offending INTEGER;
BEGIN
    SELECT COUNT(*) INTO offending FROM bookings
     WHERE (origin = 'CHANNEL' AND (external_source IS NULL OR external_ref IS NULL))
        OR (origin <> 'CHANNEL' AND (external_source IS NOT NULL OR external_ref IS NOT NULL));
    IF offending = 0 THEN
        ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_external_ref_pairing;
        RAISE NOTICE 'V85: ck_booking_external_ref_pairing validated';
    ELSE
        RAISE WARNING 'V85: % row(s) violate the origin/external-ref pairing; constraint left NOT VALID', offending;
    END IF;
END $$;

-- A channel cancellation arrives keyed by the provider's own reference, so that
-- lookup must be indexed. Partial: only CHANNEL rows carry the columns at all.
CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_external_ref
    ON bookings (external_source, external_ref)
    WHERE external_source IS NOT NULL AND external_ref IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_booking_origin_binge
    ON bookings (binge_id, origin)
    WHERE origin <> 'DIRECT';

COMMENT ON COLUMN bookings.origin IS
    'How the reservation was created: DIRECT (customer self-service), ADMIN (staff/walk-in), CHANNEL (external sales channel). Drives which anti-abuse guards apply — see BookingOriginPolicy.';
COMMENT ON COLUMN bookings.external_source IS
    'Provider-neutral slug identifying the originating channel. NULL unless origin = CHANNEL. Booking-service never interprets this value.';
COMMENT ON COLUMN bookings.external_ref IS
    'The channel''s own booking reference. Unique per source. Required for reconciliation and for matching inbound cancellations.';

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: `DROP CONSTRAINT IF EXISTS` for ck_booking_origin and
-- ck_booking_external_ref_pairing, both re-added in the same migration. Idempotent
-- replace; no data touched.
