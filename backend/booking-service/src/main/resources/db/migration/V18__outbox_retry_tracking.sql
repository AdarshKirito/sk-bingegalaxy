-- V18: Outbox retry tracking for booking-service.
-- Mirrors payment-service V6: allows the poller to continue past a poison event instead
-- of breaking out of the batch. Same column names / semantics across services for consistency.

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS failed_permanent BOOLEAN NOT NULL DEFAULT FALSE;

-- Replace the old (sent, created_at) index with one that also skips poisoned rows
-- so the "pending work" query remains a single index scan under load.
DROP INDEX IF EXISTS idx_outbox_sent;
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_event (sent, failed_permanent, created_at);
