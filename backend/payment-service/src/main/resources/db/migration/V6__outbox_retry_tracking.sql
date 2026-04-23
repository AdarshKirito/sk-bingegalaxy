-- Add retry tracking to outbox so poison-pill events don't block the queue forever.
-- Events that fail more than MAX_ATTEMPTS are marked as "failed_permanent=true"
-- and skipped by the publisher. Operations can then review and either fix + retry
-- or discard.

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS attempts          INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_error        VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS failed_permanent  BOOLEAN   NOT NULL DEFAULT FALSE;

-- Replace the old index to include the permanent-failure flag so the publisher
-- query efficiently skips poisoned rows.
DROP INDEX IF EXISTS idx_outbox_sent;
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_event (sent, failed_permanent, created_at);
