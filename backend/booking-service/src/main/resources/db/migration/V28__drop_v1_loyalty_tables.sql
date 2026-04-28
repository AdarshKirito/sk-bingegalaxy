-- ============================================================================
-- V28__drop_v1_loyalty_tables.sql
-- ----------------------------------------------------------------------------
-- M13 cutover: retire the legacy v1 loyalty ledger.
--
-- The v2 system (loyalty_membership, loyalty_points_wallet, loyalty_ledger_entry)
-- has been the single source of truth since M12.  The V22 backfill enrolled
-- every legacy customer into v2 and carried over their tier; LoyaltyService
-- delegates 100% of reads/writes to the v2 engines as of M13.
--
-- These two tables are no longer referenced by any application code.  Drop
-- them to remove the dead schema and reclaim the disk.  Idempotent: uses
-- IF EXISTS so the migration is safe to re-run on environments that have
-- already been cleaned manually.
--
-- Rollback: restore from the database backup taken before M13 deploy. The
-- v2 wallet ledger contains every transaction these tables held — the
-- backfill copied them over with full provenance.
-- ============================================================================

DROP TABLE IF EXISTS loyalty_transactions CASCADE;
DROP TABLE IF EXISTS loyalty_accounts     CASCADE;
