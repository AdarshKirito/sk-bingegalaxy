-- Drop dead per-binge loyalty columns introduced in V12.
--
-- These columns have been unused since V17 made loyalty accounts
-- system-level (no binge_id) and the earn/redeem rates moved to the
-- system-wide `app.loyalty.*` properties (see LoyaltyProperties.java).
-- No Java entity, repository, controller, or frontend page reads them.
--
-- Safe to drop: every active read path goes through LoyaltyProperties
-- (system) and the loyalty_accounts / loyalty_transactions tables.

ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_enabled;
ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_points_per_rupee;
ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_redemption_rate;
