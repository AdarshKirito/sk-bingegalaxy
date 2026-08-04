-- V84: per-venue booking-window rules (gap G5) and the permitted-duration
-- allow-list (decision B5).
--
-- G5 — minimum notice and maximum advance.
--   Today the only guards are "the slot has not already started" and a GLOBAL
--   app.booking.max-booking-horizon-days (default 365). A venue therefore cannot
--   say "we need 2 hours' warning" or "we only publish 90 days out". Direct
--   customers rarely book at 23:58 for 00:30; a sales channel will, because it
--   has no idea the venue is shut.
--
-- B5 — permitted durations.
--   Duration is currently free choice across 30..720 in 30-minute steps: 24
--   possibilities per start time. A reseller catalogue has to enumerate those as
--   discrete options, so the calendar explodes combinatorially. Capping the set
--   at 4 keeps a channel option picker usable and the availability feed finite,
--   without taking the choice away from the venue.
--
-- Both are NULL-means-inherit, matching binges.open_time / event_types.setup_minutes.

-- ── G5: per-venue booking window ────────────────────────────────────────
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS min_notice_minutes  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_advance_days    INTEGER;

ALTER TABLE binges
    DROP CONSTRAINT IF EXISTS ck_binge_min_notice_minutes,
    DROP CONSTRAINT IF EXISTS ck_binge_max_advance_days;
ALTER TABLE binges
    ADD CONSTRAINT ck_binge_min_notice_minutes
        -- 0 .. 30 days. A venue needing more than a month of notice is not
        -- running an online booking product.
        CHECK (min_notice_minutes BETWEEN 0 AND 43200),
    ADD CONSTRAINT ck_binge_max_advance_days
        CHECK (max_advance_days IS NULL OR max_advance_days BETWEEN 1 AND 730);

COMMENT ON COLUMN binges.min_notice_minutes IS
    'Minimum lead time before a booking may start. 0 = same-minute booking allowed. Enforced venue-local, so it is correct in every timezone.';
COMMENT ON COLUMN binges.max_advance_days IS
    'How far ahead this venue publishes availability. NULL inherits the platform-wide app.booking.max-booking-horizon-days (default 365).';

-- ── B5: permitted durations per event type ──────────────────────────────
-- Stored as a compact CSV of minute values rather than a child table: it is a
-- tiny ordered set read on every availability request and never queried
-- relationally. A join table would cost an N+1 on the hottest read path and buy
-- nothing. Validated in Java (BookingWindowPolicy) and shape-guarded here.
ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS permitted_durations_csv VARCHAR(64);

ALTER TABLE event_types
    DROP CONSTRAINT IF EXISTS ck_event_type_permitted_durations;
ALTER TABLE event_types
    ADD CONSTRAINT ck_event_type_permitted_durations
        CHECK (
            permitted_durations_csv IS NULL
            OR permitted_durations_csv ~ '^[0-9]{2,3}(,[0-9]{2,3}){0,3}$'
        );

COMMENT ON COLUMN event_types.permitted_durations_csv IS
    'B5: up to 4 comma-separated durations in minutes (e.g. "120,180,240") that customers and channels may pick. NULL = any 30-minute multiple between min_hours and max_hours. Capped at 4 so a reseller option picker stays usable.';

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: three `DROP CONSTRAINT IF EXISTS` statements, each immediately followed by
-- ADD CONSTRAINT with the new bounds. Idempotent replace; no data touched.
