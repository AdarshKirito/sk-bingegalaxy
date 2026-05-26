-- V59 — Enforce DB-level NOT NULL on add_ons.category_id.
--
-- Context
-- -------
-- V55 introduced addon_categories + add_ons.category_id (NULLABLE) and dual-
-- wrote alongside the legacy add_ons.category VARCHAR. V58 dropped the
-- legacy VARCHAR column. AddOnSaveRequest.categoryId is annotated @NotNull
-- on the API surface, so all NEW add-ons must carry a category — but the
-- column is still nullable at the DB layer. That gap allows drift if any
-- legacy row was missed by the V55/V58 backfill (e.g. trailing whitespace
-- in the dropped VARCHAR, or rows inserted directly via SQL bypassing the
-- DTO validator).
--
-- Production-grade rule: schema is the source of truth. This migration
-- closes the gap by:
--   1. Guaranteeing every add-on row resolves to a real addon_categories row
--      (creating an "Uncategorized" fallback per binge for any orphan), then
--   2. Enforcing NOT NULL at the column level.
--
-- All steps are idempotent / safe to re-run.

-- ─────────────────────────────────────────────────────────────────────────
-- 1) For every binge (incl. NULL = global) that still has an add-on with
--    NULL category_id, create an "Uncategorized" addon_categories row.
--    Uses IS NOT DISTINCT FROM so NULL bingeId matches NULL bingeId.
-- ─────────────────────────────────────────────────────────────────────────
INSERT INTO addon_categories (binge_id, name, description, sort_order, active)
SELECT DISTINCT
       a.binge_id,
       'Uncategorized',
       'Auto-created by V59 to backfill add-ons missing a category',
       999,
       TRUE
FROM   add_ons a
WHERE  a.category_id IS NULL
  AND  NOT EXISTS (
        SELECT 1
        FROM   addon_categories c
        WHERE  c.name = 'Uncategorized'
          AND  (c.binge_id IS NOT DISTINCT FROM a.binge_id)
  );

-- ─────────────────────────────────────────────────────────────────────────
-- 2) Link orphan add-ons to that "Uncategorized" row in their tenant scope.
-- ─────────────────────────────────────────────────────────────────────────
UPDATE add_ons a
SET    category_id = c.id
FROM   addon_categories c
WHERE  a.category_id IS NULL
  AND  c.name = 'Uncategorized'
  AND  (c.binge_id IS NOT DISTINCT FROM a.binge_id);

-- ─────────────────────────────────────────────────────────────────────────
-- 3) Defensive safety net — if any rows still have NULL category_id at this
--    point, fail loudly rather than silently allow ALTER ... SET NOT NULL
--    to fail with a confusing error.
-- ─────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM add_ons WHERE category_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V59 cannot enforce NOT NULL: % add_ons rows still have NULL category_id after backfill',
            orphan_count;
    END IF;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────
-- 4) Enforce NOT NULL. From this migration onward the DB rejects any insert
--    or update that omits category_id, matching the API-level @NotNull
--    contract on AddOnSaveRequest.
-- ─────────────────────────────────────────────────────────────────────────
ALTER TABLE add_ons ALTER COLUMN category_id SET NOT NULL;
