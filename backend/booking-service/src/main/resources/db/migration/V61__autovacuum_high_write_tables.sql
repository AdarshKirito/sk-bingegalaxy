-- =============================================================================
-- V61: Autovacuum tuning for high-write tables + REINDEX scheduling
--
-- Problem: Postgres autovacuum runs on default thresholds (20 % dead-tuple
-- ratio). Outbox, event logs, and audit tables accumulate dead tuples far
-- faster than average — index pages balloon, query plans stop using them,
-- and p95 booking-creation latency drifts from ~668 ms toward 5 s+ before
-- anyone notices why.
--
-- Fix 1: lower autovacuum thresholds on the five highest-write tables so
--   vacuum runs after every ~200 dead tuples rather than waiting for thousands.
--
-- Fix 2: add a comment block describing the weekly REINDEX CronJob that
--   complements autovacuum (see k8s/postgres-maintenance.yml).
--
-- These are storage-parameter changes (no schema changes) and are safe to
-- run on a live database — they take effect on the next autovacuum cycle.
-- =============================================================================

-- ── Outbox table ─────────────────────────────────────────────────────────────
-- Rows are inserted and soft-deleted / status-updated at every booking step.
-- Default scale_factor=0.2 means vacuum waits for 20% of rows to be dead —
-- at 10k rows that's 2000 dead tuples. We tighten to 1% + 50 absolute rows.
ALTER TABLE outbox_event SET (
    autovacuum_vacuum_scale_factor    = 0.01,
    autovacuum_vacuum_threshold       = 50,
    autovacuum_analyze_scale_factor   = 0.005,
    autovacuum_analyze_threshold      = 50,
    autovacuum_vacuum_cost_delay      = 2
);

-- ── Booking event log ─────────────────────────────────────────────────────────
ALTER TABLE booking_event_log SET (
    autovacuum_vacuum_scale_factor    = 0.01,
    autovacuum_vacuum_threshold       = 50,
    autovacuum_analyze_scale_factor   = 0.005,
    autovacuum_analyze_threshold      = 50,
    autovacuum_vacuum_cost_delay      = 2
);

-- ── Processed Outbox events (high INSERT + periodic delete) ──────────────────
ALTER TABLE processed_event SET (
    autovacuum_vacuum_scale_factor    = 0.01,
    autovacuum_vacuum_threshold       = 50,
    autovacuum_analyze_scale_factor   = 0.005,
    autovacuum_analyze_threshold      = 50
);

-- ── Loyalty ledger (append-heavy, rarely updated) ────────────────────────────
ALTER TABLE loyalty_ledger_entry SET (
    autovacuum_vacuum_scale_factor    = 0.02,
    autovacuum_vacuum_threshold       = 100,
    autovacuum_analyze_scale_factor   = 0.01,
    autovacuum_analyze_threshold      = 100
);

-- ── Waitlist entries (high churn: insert + cancel + promote cycles) ───────────
ALTER TABLE waitlist_entries SET (
    autovacuum_vacuum_scale_factor    = 0.01,
    autovacuum_vacuum_threshold       = 50,
    autovacuum_analyze_scale_factor   = 0.005,
    autovacuum_analyze_threshold      = 50
);

-- =============================================================================
-- NOTE: The k8s/postgres-maintenance.yml CronJob runs
--   REINDEX CONCURRENTLY on these tables every Sunday at 02:30 UTC.
-- REINDEX reclaims index bloat that autovacuum cannot remove (index pages
-- are not compacted by VACUUM, only by REINDEX or pg_repack).
-- =============================================================================
