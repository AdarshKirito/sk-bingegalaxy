-- V45 — Add-on inventory, event-type guest range, late-arrival flag.
--
-- Three independent additions, all nullable / defaulted so the migration is
-- safe to run online with concurrent traffic.
--
-- 1) add_ons.stock_per_day             — daily inventory cap (NULL = unlimited).
-- 2) add_ons.advance_notice_minutes    — minimum lead-time before booking start
--                                        the add-on may be ordered (NULL = none).
-- 3) event_types.min_guests/max_guests — per-event-type guest range
--                                        (NULL = no constraint at that bound).
-- 4) bookings.late_arrival             — boolean, set when a check-in occurs
--                                        after start_time + grace; powers the
--                                        derived "LATE_ARRIVAL" UI state.

ALTER TABLE add_ons
    ADD COLUMN IF NOT EXISTS stock_per_day INTEGER,
    ADD COLUMN IF NOT EXISTS advance_notice_minutes INTEGER;

ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS min_guests INTEGER,
    ADD COLUMN IF NOT EXISTS max_guests INTEGER;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS late_arrival BOOLEAN NOT NULL DEFAULT FALSE;

-- Sanity constraint: when both bounds are set, min must not exceed max.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_event_types_guest_range'
    ) THEN
        ALTER TABLE event_types
            ADD CONSTRAINT chk_event_types_guest_range
            CHECK (min_guests IS NULL OR max_guests IS NULL OR min_guests <= max_guests);
    END IF;
END $$;

-- Sanity constraint: stock_per_day must be non-negative when set.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_addons_stock_nonneg'
    ) THEN
        ALTER TABLE add_ons
            ADD CONSTRAINT chk_addons_stock_nonneg
            CHECK (stock_per_day IS NULL OR stock_per_day >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_addons_advance_notice_nonneg'
    ) THEN
        ALTER TABLE add_ons
            ADD CONSTRAINT chk_addons_advance_notice_nonneg
            CHECK (advance_notice_minutes IS NULL OR advance_notice_minutes >= 0);
    END IF;
END $$;
