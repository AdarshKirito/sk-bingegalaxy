-- V23: Add missing foreign-key constraints from binge-scoped child tables to
-- the binges table. These FKs prevent orphaned rows if a binge is deleted
-- and prevent inserts that reference a non-existent binge_id.
--
-- All these tables live in the same booking_db, so a real DB-level FK is
-- both possible and correct here. Cross-DB references (e.g. customer_id
-- pointing at auth_db.users, or availability_db.blocked_slots.binge_id)
-- can NOT be enforced at the DB layer and stay as application-level
-- contracts.
--
-- ON DELETE policy:
--   * NULLABLE binge_id -> SET NULL (preserves history if binge is purged)
--   * NOT NULL binge_id -> CASCADE  (child has no meaning without parent)
--
-- Migration is fully idempotent: it skips constraints that already exist
-- and uses NOT VALID so it doesn't have to scan the entire child table
-- with an exclusive lock; we then VALIDATE separately, which only takes
-- a SHARE lock.

DO $$
BEGIN
  -- add_ons.binge_id (nullable)
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_add_ons_binge'
  ) THEN
    ALTER TABLE add_ons
      ADD CONSTRAINT fk_add_ons_binge
      FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE SET NULL NOT VALID;
    ALTER TABLE add_ons VALIDATE CONSTRAINT fk_add_ons_binge;
  END IF;

  -- event_types.binge_id (nullable)
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_types_binge'
  ) THEN
    ALTER TABLE event_types
      ADD CONSTRAINT fk_event_types_binge
      FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE SET NULL NOT VALID;
    ALTER TABLE event_types VALIDATE CONSTRAINT fk_event_types_binge;
  END IF;

  -- rate_codes.binge_id (nullable)
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_rate_codes_binge'
  ) THEN
    ALTER TABLE rate_codes
      ADD CONSTRAINT fk_rate_codes_binge
      FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE SET NULL NOT VALID;
    ALTER TABLE rate_codes VALIDATE CONSTRAINT fk_rate_codes_binge;
  END IF;

  -- customer_pricing_profiles.binge_id (nullable; customer_id is in auth_db -> no FK)
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_cust_pricing_binge'
  ) THEN
    ALTER TABLE customer_pricing_profiles
      ADD CONSTRAINT fk_cust_pricing_binge
      FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE SET NULL NOT VALID;
    ALTER TABLE customer_pricing_profiles VALIDATE CONSTRAINT fk_cust_pricing_binge;
  END IF;

  -- cancellation_tiers.binge_id (NOT NULL -> cascade)
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_cancel_tier_binge'
  ) THEN
    ALTER TABLE cancellation_tiers
      ADD CONSTRAINT fk_cancel_tier_binge
      FOREIGN KEY (binge_id) REFERENCES binges(id) ON DELETE CASCADE NOT VALID;
    ALTER TABLE cancellation_tiers VALIDATE CONSTRAINT fk_cancel_tier_binge;
  END IF;
END
$$;
