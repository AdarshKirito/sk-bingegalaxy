-- V90: a channel reservation's identity is (VENUE, source, reference) — not
-- (source, reference).
--
-- V85 made the pair (external_source, external_ref) globally unique. That is the
-- right guard for ONE venue and the wrong one for a platform: external_source is
-- the destination slug ('simulator', 'bokun', ...), which every venue connected to
-- that destination shares, and external_ref is a reference the RESELLER chooses.
-- Two venues on the same destination therefore live in one flat namespace keyed by
-- a value neither of them controls.
--
-- Three concrete failures, all silent:
--
--   1. Venue B's reservation carrying a reference venue A already used is answered
--      200 "already recorded" with VENUE A'S BOOKING — so the reseller is handed
--      another venue's booking reference, and venue B never receives the sale.
--   2. A cancellation addressed by (source, ref) resolves to whichever venue got
--      there first, so venue B's cancel can cancel VENUE A'S BOOKING.
--   3. A reseller that numbers its bookings 1, 2, 3 (or reuses a test uuid) makes
--      both of the above ordinary rather than exotic.
--
-- Adding binge_id to the key makes each venue's channel namespace its own. The
-- guarantee V85 wanted — a redelivered reservation converges on the original
-- rather than double-booking the venue — is unchanged, because a redelivery
-- carries the same venue as the original.
--
-- The application must now address channel reservations by all three columns; a
-- lookup that omits binge_id is exactly the cross-venue read this migration
-- exists to stop, so BookingRepository no longer exposes the two-column finder.

-- Created BEFORE the old index is dropped: the new key is a strict superset of the
-- old one, so at no point is the table unprotected against a redelivered
-- reservation becoming a second booking.
CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_external_ref_venue
    ON bookings (binge_id, external_source, external_ref)
    WHERE external_source IS NOT NULL AND external_ref IS NOT NULL;

DROP INDEX IF EXISTS uk_booking_external_ref;

COMMENT ON COLUMN bookings.external_ref IS
    'The channel''s own booking reference. Unique per (binge_id, external_source) — '
    'NOT globally: the reseller chooses this value and every venue on a destination '
    'shares the same external_source, so a global key would let one venue''s '
    'reservation resolve to another venue''s booking. Required for reconciliation '
    'and for matching inbound cancellations.';

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: `DROP INDEX IF EXISTS uk_booking_external_ref`, replaced in the same
-- migration by the wider uk_booking_external_ref_venue created immediately above
-- it. No data is touched and no uniqueness window is opened — the replacement is
-- created first, and it constrains a superset of the columns the dropped index did.
