-- =============================================================================
-- V50 — Align `billing_addresses` column names with JPA entity (BillingAddress).
--
-- Problem
-- -------
-- V39 created the table with columns `line1` / `line2`, but the JPA entity
-- `com.skbingegalaxy.booking.entity.BillingAddress` maps those fields to
-- `address_line1` / `address_line2`. Hibernate schema validation therefore
-- failed at startup with:
--     Schema-validation: missing column [address_line1] in table [billing_addresses]
--
-- Fix
-- ---
-- Rename the columns to the entity-canonical names. Idempotent guards make
-- the migration safe to re-run if a hot-fix has already been applied
-- manually in any environment.
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'billing_addresses' AND column_name = 'line1'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'billing_addresses' AND column_name = 'address_line1'
    ) THEN
        EXECUTE 'ALTER TABLE billing_addresses RENAME COLUMN line1 TO address_line1';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'billing_addresses' AND column_name = 'line2'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'billing_addresses' AND column_name = 'address_line2'
    ) THEN
        EXECUTE 'ALTER TABLE billing_addresses RENAME COLUMN line2 TO address_line2';
    END IF;
END$$;

-- The original DDL declared `line1` as NOT NULL. Keep that constraint on the
-- renamed `address_line1` column (the rename preserves it, but be explicit).
ALTER TABLE billing_addresses
    ALTER COLUMN address_line1 SET NOT NULL;
