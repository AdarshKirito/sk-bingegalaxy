-- V88 — attribution capture (distribution design G-B).
--
-- WHY -----------------------------------------------------------------------
-- Google Things to Do is a product FEED plus a DEEP LINK. Google never takes the
-- booking; the traveller completes checkout on SK Binge. So a Google conversion is an
-- ordinary DIRECT booking, and the ONLY evidence that Google produced it is a marketing
-- parameter on the landing URL. Without capturing that, the entire Google channel is
-- unmeasurable and the business case for building it is unprovable.
--
-- NOT THE SAME AS external_source / external_ref (V85/V86) -------------------
--   external_source  = the reservation ARRIVED FROM a channel  -> origin = CHANNEL
--   attribution_*    = a DIRECT booking INFLUENCED by a referral -> origin = DIRECT
-- Collapsing them would make a Google referral look like a Viator reservation: it would
-- inherit CHANNEL's origin guards, skip the customer funnel checks that DIRECT bookings
-- must pass, and be counted as channel-collected revenue that no one is going to remit.
-- They are deliberately separate columns.
--
-- REPORTING DIMENSION ONLY --------------------------------------------------
-- Attribution must never alter price, availability or eligibility. There is no index
-- or constraint here that any pricing or availability query could join on, and nothing
-- in the booking path reads these columns. If a future change makes attribution
-- influence what a customer pays, that is a discount/campaign feature and needs its own
-- design -- not a reinterpretation of a reporting field.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS attribution_source      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS attribution_ref         VARCHAR(128),
    ADD COLUMN IF NOT EXISTS attribution_captured_at TIMESTAMP;

COMMENT ON COLUMN bookings.attribution_source IS
    'Lowercased marketing source for a DIRECT booking (e.g. google_things_to_do). Reporting only - never affects price, availability or eligibility. Distinct from external_source, which means the reservation arrived FROM a channel.';
COMMENT ON COLUMN bookings.attribution_ref IS
    'Opaque click/campaign id from the referring link. Stored verbatim; never parsed for meaning.';

-- Canonical lowercase, same rule and same reasoning as V86's external_source: reject the
-- write rather than accept mixed case and paper over it with lower() at read time. A
-- mixed-case row means a caller skipped normalisation, and that should fail at the
-- source. NOT VALID because it only needs to govern future writes; the column is new so
-- there is nothing to scan, but the marker keeps the intent explicit.
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_attribution_source_canonical
        CHECK (
            attribution_source IS NULL
            OR (attribution_source = lower(attribution_source)
                AND attribution_source = btrim(attribution_source)
                AND attribution_source <> '')
        )
        NOT VALID;

ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_attribution_source_canonical;

-- An attribution_ref without a source is meaningless (a click id belonging to nobody),
-- and would quietly corrupt any "conversions by source" report by existing outside every
-- bucket. A source without a ref is fine and common: plain utm_source with no click id.
ALTER TABLE bookings
    ADD CONSTRAINT ck_booking_attribution_pairing
        CHECK (attribution_ref IS NULL OR attribution_source IS NOT NULL)
        NOT VALID;

ALTER TABLE bookings VALIDATE CONSTRAINT ck_booking_attribution_pairing;

-- Reporting index: "how many bookings did source X produce in period Y". Partial, so it
-- costs nothing for the overwhelming majority of bookings that carry no attribution --
-- which is every booking made before this migration and every organic one after it.
CREATE INDEX IF NOT EXISTS idx_booking_attribution_source_date
    ON bookings (attribution_source, booking_date)
    WHERE attribution_source IS NOT NULL;
