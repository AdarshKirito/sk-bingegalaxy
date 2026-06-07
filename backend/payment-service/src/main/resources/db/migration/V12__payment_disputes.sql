-- V12: Payment dispute (chargeback) tracking.
-- Stores the full lifecycle of every Razorpay dispute event so ops can
-- triage open disputes, gather evidence within the gateway's response
-- window, and audit outcomes. The table is append-friendly — rows are
-- inserted on dispute.created and updated on subsequent lifecycle events.

CREATE TABLE IF NOT EXISTS payment_disputes (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT        NOT NULL REFERENCES payments(id),
    gateway_dispute_id  VARCHAR(120)  NOT NULL,
    binge_id            BIGINT,
    booking_ref         VARCHAR(60)   NOT NULL,
    amount              DECIMAL(10,2) NOT NULL,
    currency            VARCHAR(8)    NOT NULL DEFAULT 'INR',
    -- OPEN | UNDER_REVIEW | WON | LOST | ACCEPTED
    status              VARCHAR(20)   NOT NULL,
    reason_code         VARCHAR(80),
    reason_description  VARCHAR(500),
    respond_by          TIMESTAMP,
    gateway_created_at  TIMESTAMP,
    -- Full raw JSON payload preserved for evidence submission to Razorpay
    raw_payload         TEXT,
    ops_notes           TEXT,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_dispute_gateway_id UNIQUE (gateway_dispute_id)
);

-- Admin open-dispute queue: filter by binge, sort newest first
CREATE INDEX IF NOT EXISTS idx_dispute_binge_status
    ON payment_disputes (binge_id, status, created_at DESC);

-- Lookup by payment for customer/admin payment detail view
CREATE INDEX IF NOT EXISTS idx_dispute_payment_id
    ON payment_disputes (payment_id);

-- Respond-by deadline alerting: find disputes whose window expires soon
CREATE INDEX IF NOT EXISTS idx_dispute_respond_by
    ON payment_disputes (respond_by)
    WHERE status NOT IN ('WON', 'LOST', 'ACCEPTED');

-- Admin audit trail: reverse-chronological dispute history without binge filter
CREATE INDEX IF NOT EXISTS idx_dispute_created_at
    ON payment_disputes (created_at DESC);
