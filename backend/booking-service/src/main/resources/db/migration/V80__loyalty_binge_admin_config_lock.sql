-- Super-admin governance lock on per-binge loyalty configuration.
--
-- WHY: previously any binge admin could enable/disable loyalty for their venue
-- and freely edit its earn/redeem economics. The super admin needs to gate that
-- so a venue's point value / participation can't be changed without oversight
-- (the same way goodwill budgets and the module-permission matrix are gated).
--
-- When admin_config_locked = TRUE, only a super-admin may enable/disable the
-- binding or change its earn, redemption, or perk rules; the binge's own admins
-- get a read-only view with a "managed by super admin" banner. Default FALSE
-- keeps the existing self-service behavior for every current binding.
ALTER TABLE loyalty_binge_binding
    ADD COLUMN IF NOT EXISTS admin_config_locked BOOLEAN NOT NULL DEFAULT FALSE;
