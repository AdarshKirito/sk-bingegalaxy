-- PAY-006: durable refund intents.
--
-- A refund row is now committed (refund_status = 'INITIATED', with a stable
-- gateway receipt) BEFORE the Razorpay refund API is called. If the provider
-- call is ambiguous (timeout / crash), the row stays INITIATED and the
-- reconciliation poller resolves it by looking the receipt up at the provider
-- — guaranteeing at-most-once money movement per intent.

ALTER TABLE refunds
    ADD COLUMN IF NOT EXISTS gateway_receipt VARCHAR(64);

-- One provider attempt per receipt: a duplicate insert means an intent was
-- double-created, which the reserve path must never do.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_gateway_receipt
    ON refunds (gateway_receipt)
    WHERE gateway_receipt IS NOT NULL;

-- Reconciliation scans in-flight intents by lifecycle + age.
CREATE INDEX IF NOT EXISTS idx_refunds_status_created
    ON refunds (refund_status, created_at);
