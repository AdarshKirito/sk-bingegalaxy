-- V47: Add NO_SHOW threshold for the customer-freeze policy.
--
-- Operators already have per-binge knobs for cancel-based and
-- payment-timeout-based freezes; this adds the missing third lever so a
-- repeat no-show pattern triggers the same temporary booking-flow freeze.
-- Defaults to 3 (matching the other knobs) and is hot-pluggable: setting
-- it to 0 disables NO_SHOW-based freezes while keeping the others active.
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS max_no_shows_before_freeze INTEGER NOT NULL DEFAULT 3;
