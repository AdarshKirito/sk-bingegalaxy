-- =============================================================================
-- V54 -- Loyalty v2 cutover defaults
--
-- V22 intentionally froze legacy binge bindings while v1 and v2 ran in
-- parallel.  V28 dropped the v1 tables and V30 seeded earn rules, so a frozen
-- ENABLED_LEGACY row now means the active system silently skips earn/redeem.
--
-- This migration makes v2 authoritative for existing bindings and adds a sane
-- default redemption rule anywhere one is missing.  It is idempotent and leaves
-- explicit DISABLED bindings untouched.
-- =============================================================================

UPDATE loyalty_binge_binding
SET    status = 'ENABLED',
       legacy_frozen = FALSE,
       enrolled_at = COALESCE(enrolled_at, NOW()),
       updated_at = NOW(),
       version = version + 1
WHERE  status = 'ENABLED_LEGACY'
  AND  legacy_frozen = TRUE;

INSERT INTO loyalty_binge_redemption_rule
    (binding_id, points_per_currency_unit, min_redemption_points,
     max_redemption_percent, effective_from, created_at)
SELECT
    lbb.id, 100, 100, 50.00, NOW(), NOW()
FROM loyalty_binge_binding lbb
WHERE lbb.status = 'ENABLED'
  AND NOT EXISTS (
      SELECT 1
      FROM loyalty_binge_redemption_rule rr
      WHERE rr.binding_id = lbb.id
        AND rr.effective_to IS NULL
  );
