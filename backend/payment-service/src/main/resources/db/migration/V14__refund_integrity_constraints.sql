-- DATA-002: database backstop behind the app-level over-refund guard.
--
-- The application already prevents over/duplicate refunds under a pessimistic
-- payment-row lock (PaymentService.initiateRefund / doRetryFailedRefund), but
-- any future write path that bypasses those methods (e.g. a webhook inserting
-- rows directly) had no schema-level safety net.

-- 1) A refund can never be zero or negative.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_refunds_amount_positive'
    ) THEN
        ALTER TABLE refunds
            ADD CONSTRAINT chk_refunds_amount_positive CHECK (amount > 0);
    END IF;
END $$;

-- 2) One row per gateway refund id. Real Razorpay refund ids (rfnd_...) are
--    unique at the gateway; a duplicate insert means a double-processed
--    webhook or a retry bug — reject it at the schema. FAILED attempts carry
--    NULL and are exempt (partial index).
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_gateway_refund_id
    ON refunds (gateway_refund_id)
    WHERE gateway_refund_id IS NOT NULL;

-- The old non-unique index is fully covered by the unique one.
DROP INDEX IF EXISTS idx_refund_gateway_refund_id;

-- ── Migration-safety review ───────────────────────────────────────────────
-- allow:destructive
-- Reviewed: `DROP INDEX IF EXISTS idx_refund_gateway_refund_id` replaces a non-unique
-- index with a UNIQUE one covering the same column — the point of the migration, since
-- the non-unique index allowed the duplicate-refund case this constraint closes.
