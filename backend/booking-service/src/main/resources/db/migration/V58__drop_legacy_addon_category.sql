-- V58: drop legacy add_ons.category VARCHAR column.
--
-- V55 introduced the addon_categories table and add_ons.category_id FK,
-- and ran a one-time backfill so every existing add_on got its category_id
-- populated. We then operated in dual-write mode (writing both the legacy
-- string column and the FK) for one release cycle so rollbacks could
-- safely fall back to the old code path.
--
-- Two release cycles later, the dual-write window is over. This migration
-- forecloses the legacy column for good.
--
-- Defensive backfill: any add_ons row that still has a non-null category
-- string but a null category_id (e.g. created mid-deploy between V55 and
-- the dual-write code landing) gets linked here before we drop the column.

-- 1) Catch-up: insert missing (binge_id, name) tuples into addon_categories.
INSERT INTO addon_categories (binge_id, name, description, sort_order, active)
SELECT DISTINCT a.binge_id,
                a.category,
                NULL,
                0,
                TRUE
FROM   add_ons a
WHERE  a.category IS NOT NULL
AND    a.category_id IS NULL
AND    NOT EXISTS (
    SELECT 1
    FROM   addon_categories c
    WHERE  ((c.binge_id IS NULL AND a.binge_id IS NULL)
            OR c.binge_id = a.binge_id)
    AND    LOWER(c.name) = LOWER(a.category)
);

-- 2) Catch-up: link any orphan add_ons.category strings to the FK.
UPDATE add_ons a
SET    category_id = c.id
FROM   addon_categories c
WHERE  a.category IS NOT NULL
AND    a.category_id IS NULL
AND    ((c.binge_id IS NULL AND a.binge_id IS NULL) OR c.binge_id = a.binge_id)
AND    LOWER(c.name) = LOWER(a.category);

-- 3) Drop the legacy column. After this migration the application MUST NOT
--    read or write add_ons.category — only category_id.
ALTER TABLE add_ons DROP COLUMN IF EXISTS category;
