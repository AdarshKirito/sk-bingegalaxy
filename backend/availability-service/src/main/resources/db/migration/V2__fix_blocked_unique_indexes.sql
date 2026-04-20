-- Fix BlockedDate unique index: must be per-binge for multi-tenancy
DROP INDEX IF EXISTS uk_blocked_dates_date;
CREATE UNIQUE INDEX uk_blocked_dates_binge_date ON blocked_dates (binge_id, blocked_date);

-- Fix BlockedSlot unique index: must be per-binge for multi-tenancy
DROP INDEX IF EXISTS uk_blocked_slot;
CREATE UNIQUE INDEX uk_blocked_slot_binge ON blocked_slots (binge_id, slot_date, start_hour);
