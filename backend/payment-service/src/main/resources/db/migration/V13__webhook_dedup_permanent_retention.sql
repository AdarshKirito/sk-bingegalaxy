-- =============================================================================
-- V13: Permanent retention policy for processed_webhook_event
--
-- Security context:
--   processed_webhook_event deduplicates Razorpay webhook callbacks by
--   (event_id, provider). This prevents:
--     • Duplicate webhook delivery re-triggering payment.success on the same order.
--     • Replay attack: an attacker capturing a valid HMAC webhook and replaying
--       it months later to re-credit a cancelled booking.
--
-- Retention decision:
--   This table MUST retain rows PERMANENTLY (or for the full lifetime of the
--   associated booking). Pruning by TTL would re-open the replay attack window:
--   if an event_id is deleted after 30 days, a replayed webhook with that same
--   event_id would pass deduplication and re-process.
--
--   The idempotency_key table uses 24-hour TTL (Stripe convention). That table
--   only prevents client-side accidental double-POST, not attacker replay — its
--   TTL is safe because the HMAC signature is the primary replay defence for
--   webhooks.
--
-- Space projection:
--   Each row is approximately 200 bytes (event_id VARCHAR(128) + provider
--   VARCHAR(32) + payload_hash VARCHAR(64) + received_at TIMESTAMP).
--   At 1000 webhook events/day, the table grows by ~200 KB/day = ~70 MB/year.
--   This is negligible; DO NOT add an expires_at column or scheduled pruning.
--
-- What this migration does:
--   1. Adds a table comment documenting the retention decision for future
--      developers and auditors.
--   2. Adds a partial index to speed up the idempotency lookup for the most
--      common RAZORPAY provider (covering > 99% of queries).
-- =============================================================================

COMMENT ON TABLE processed_webhook_event IS
  'Permanent deduplication table for Razorpay (and future provider) webhook callbacks. '
  'Rows MUST NOT be deleted or TTL-pruned — any deletion re-opens the replay-attack window. '
  'Space cost: ~200 bytes/row, ~70 MB/year at production volume. '
  'See V7 for original table definition. See security-test-report.md §webhook-replay for threat model.';

-- Partial index for the common single-provider query path.
-- The existing PRIMARY KEY (event_id, provider) is used for exact lookups.
-- This partial index allows a covering index scan for RAZORPAY-only queries.
CREATE INDEX IF NOT EXISTS idx_processed_webhook_razorpay
    ON processed_webhook_event (event_id)
    WHERE provider = 'RAZORPAY';

-- Ensure received_at index exists for monitoring queries
-- (e.g. "how many callbacks in the last hour").
CREATE INDEX IF NOT EXISTS idx_processed_webhook_received_at
    ON processed_webhook_event (received_at);
