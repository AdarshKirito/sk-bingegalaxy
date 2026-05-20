-- V10: Granular refund lifecycle on each Refund row.
-- Decouples per-attempt refund state from the parent Payment.status (which is
-- the booking-level money summary). Enables: failed-refund admin queue,
-- customer refund timeline, and an async gateway path in the future.
ALTER TABLE refunds
    ADD COLUMN IF NOT EXISTS refund_status   VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    ADD COLUMN IF NOT EXISTS retry_of_id     BIGINT NULL,
    ADD COLUMN IF NOT EXISTS retry_count     INTEGER NOT NULL DEFAULT 0;

-- Self-FK so a SUPERSEDED row can be linked to the new INITIATED retry row.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_refunds_retry_of'
    ) THEN
        ALTER TABLE refunds
            ADD CONSTRAINT fk_refunds_retry_of
            FOREIGN KEY (retry_of_id) REFERENCES refunds(id) ON DELETE SET NULL;
    END IF;
END$$;

-- Hot-path indexes:
--   1) Admin failed-refund queue: filter by refund_status, sort by created_at DESC.
--   2) Customer refund timeline by bookingRef: served via Payment join, no extra index needed.
CREATE INDEX IF NOT EXISTS idx_refund_status_created
    ON refunds (refund_status, created_at DESC);

-- Backfill: existing rows are historical successes (parent payment.status was already
-- transitioned), so SUCCEEDED is the safe default — already applied via column DEFAULT.
