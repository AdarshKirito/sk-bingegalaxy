-- Drop dead per-binge loyalty columns introduced in V12.
--
-- These columns have been unused since V17 made loyalty accounts
-- system-level (no binge_id). Runtime earn/redeem rules now live in
-- loyalty_binge_earning_rule and loyalty_binge_redemption_rule.
-- No Java entity, repository, controller, or frontend page reads them.
--
-- Safe to drop: v2 reads from the loyalty_* rule, wallet, and ledger tables.

ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_enabled;
ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_points_per_rupee;
ALTER TABLE binges DROP COLUMN IF EXISTS loyalty_redemption_rate;
