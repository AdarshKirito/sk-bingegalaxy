-- V60 — Switch add_ons.category_id FK from ON DELETE SET NULL to ON DELETE
-- RESTRICT.
--
-- Context
-- -------
-- V55 originally declared:
--     ALTER TABLE add_ons ADD COLUMN category_id BIGINT
--         REFERENCES addon_categories (id) ON DELETE SET NULL;
-- which made sense while category_id was nullable. V59 enforced NOT NULL
-- on add_ons.category_id, which makes ON DELETE SET NULL broken: deleting
-- an addon_categories row that still has add-ons referencing it would
-- attempt to set their category_id to NULL and fail with a confusing
-- "null value in column ... violates not-null constraint" error instead
-- of a clean FK violation.
--
-- Production-grade rule: schema constraints should produce the *clearest
-- possible* error. ON DELETE RESTRICT will reject the category delete up
-- front with a foreign-key violation that the application layer translates
-- to a BusinessException. The application layer also pre-checks with
-- countByCategoryId so the user sees a friendly message and never reaches
-- the DB error in the happy path.
--
-- This migration is idempotent: it discovers the actual FK constraint name
-- from information_schema (Postgres auto-generates names) and re-creates
-- it with the desired action.

DO $$
DECLARE
    fk_name TEXT;
BEGIN
    -- Find the existing FK constraint on add_ons.category_id pointing at
    -- addon_categories.id. There should be at most one.
    SELECT tc.constraint_name INTO fk_name
    FROM   information_schema.table_constraints   tc
    JOIN   information_schema.key_column_usage    kcu
           ON tc.constraint_name = kcu.constraint_name
          AND tc.table_schema    = kcu.table_schema
    JOIN   information_schema.constraint_column_usage ccu
           ON tc.constraint_name = ccu.constraint_name
          AND tc.table_schema    = ccu.table_schema
    WHERE  tc.constraint_type = 'FOREIGN KEY'
      AND  tc.table_name      = 'add_ons'
      AND  kcu.column_name    = 'category_id'
      AND  ccu.table_name     = 'addon_categories'
    LIMIT 1;

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE add_ons DROP CONSTRAINT %I', fk_name);
    END IF;

    -- Re-create with RESTRICT. Named constraint so future migrations can
    -- target it explicitly.
    ALTER TABLE add_ons
        ADD CONSTRAINT fk_add_ons_category_id
        FOREIGN KEY (category_id)
        REFERENCES addon_categories (id)
        ON DELETE RESTRICT;
END
$$;
