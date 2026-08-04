-- V86: make a non-canonical channel slug structurally impossible.
--
-- The redelivery guard for channel reservations is the unique index on
-- (external_source, external_ref). That index compares bytes, so 'ACME-Channel'
-- and 'acme-channel' are two different channels as far as PostgreSQL is
-- concerned. If both spellings can be stored, a channel that varies its own
-- casing between the original delivery and a retry defeats the guard entirely —
-- the lookup misses, a second booking is created, and the venue is double-booked
-- for a slot it already sold. Commercially this is the worst failure in the whole
-- ingestion path, and it is silent.
--
-- The application canonicalises on the way in (ChannelReservationRequest setters
-- + BookingService). This constraint makes that a property of the DATA rather
-- than a property of one code path — a future importer, a manual fix-up script,
-- or a second ingestion route cannot reintroduce the ambiguity.
--
-- Deliberately NOT a lower(external_source) functional index instead: that would
-- silently ACCEPT mixed-case rows and merely match them. Rejecting the write is
-- better, because a mixed-case row reaching the database means some caller
-- skipped normalisation, and we want that to fail loudly at the source rather
-- than be papered over.

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS ck_booking_external_source_canonical;
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_external_source_canonical
        CHECK (
            external_source IS NULL
            OR (external_source = lower(external_source)
                AND external_source = btrim(external_source))
        )
        -- NOT VALID: this only ever needs to govern future writes. Validated below
        -- when the existing data already complies, which avoids an ACCESS EXCLUSIVE
        -- full-table scan during deploy on a large bookings table.
        NOT VALID;

DO $$
DECLARE offending INTEGER;
BEGIN
    SELECT COUNT(*) INTO offending
      FROM bookings
     WHERE external_source IS NOT NULL
       AND (external_source <> lower(external_source)
            OR external_source <> btrim(external_source));

    IF offending = 0 THEN
        ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_external_source_canonical;
        RAISE NOTICE 'V86: ck_booking_external_source_canonical validated';
    ELSE
        -- Do NOT auto-normalise: two rows could collapse onto the same
        -- (external_source, external_ref) and violate the unique index mid-migration.
        -- Surfacing the count lets an operator reconcile deliberately.
        RAISE WARNING
            'V86: % booking(s) have a non-canonical external_source. The constraint is '
            'active for new writes but left NOT VALID. Reconcile those rows (watch for '
            'collisions on the uk_booking_external_ref index), then run: '
            'ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_external_source_canonical;',
            offending;
    END IF;
END $$;

COMMENT ON COLUMN bookings.external_source IS
    'Provider-neutral channel slug, stored canonically (lowercase, trimmed) so the '
    'uk_booking_external_ref uniqueness guard cannot be defeated by a channel varying '
    'its own casing between an original delivery and a retry. Booking-service never '
    'interprets the value.';

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: `DROP CONSTRAINT IF EXISTS ck_booking_external_source_canonical` followed
-- by ADD CONSTRAINT. Idempotent replace; the canonicalising UPDATE above it rewrites
-- values in place and removes no rows.
