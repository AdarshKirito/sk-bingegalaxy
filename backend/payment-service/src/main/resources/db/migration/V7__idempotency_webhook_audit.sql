-- =============================================================================
-- V7: Production-grade idempotency, webhook dedup, and audit log
--
-- Stripe-style Idempotency-Key pattern:
--   • Key is provided by the client on any monetary POST.
--   • (key, request_hash) must match on replay — else 409 to surface client bugs.
--   • Cached response is returned on exact replay until expires_at.
--
-- Webhook / provider callback dedup:
--   • Keyed on the gateway-assigned identity tuple so duplicate deliveries
--     (Razorpay retries, manual replay, worker crash) are short-circuited
--     even when the underlying payment state is unchanged.
--
-- Audit log:
--   • Append-only trail of money-moving actions (refund, cancel, cash record,
--     manual add-payment) for finance reconciliation and support forensics.
-- =============================================================================

CREATE TABLE IF NOT EXISTS idempotency_key (
    -- Composite natural key: client-supplied key scoped by (method, path, user).
    -- Scoping prevents a key reused across endpoints from colliding.
    idempotency_key VARCHAR(128) NOT NULL,
    http_method     VARCHAR(8)   NOT NULL,
    request_path    VARCHAR(255) NOT NULL,
    user_id         BIGINT,

    -- SHA-256 over canonical request payload. Mismatch => 409 Conflict.
    request_hash    VARCHAR(64)  NOT NULL,

    -- Cached response (status + JSON body) returned on exact replay.
    response_status INT          NOT NULL,
    response_body   TEXT,

    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Stripe uses 24h retention; we follow. Scheduler prunes expired rows.
    expires_at      TIMESTAMP    NOT NULL,

    PRIMARY KEY (idempotency_key, http_method, request_path, user_id)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at ON idempotency_key (expires_at);


CREATE TABLE IF NOT EXISTS processed_webhook_event (
    -- Gateway-scoped natural key. For Razorpay we use (orderId + paymentId + status).
    -- Any duplicate or out-of-order delivery with the same tuple is a no-op.
    event_id     VARCHAR(128) NOT NULL,
    provider     VARCHAR(32)  NOT NULL DEFAULT 'RAZORPAY',
    payload_hash VARCHAR(64),
    received_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_processed_webhook_received ON processed_webhook_event (received_at);


CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL PRIMARY KEY,
    -- Who did it: admin email / SYSTEM / customer id. Never null — we always
    -- know who initiated a money-moving action by the time we log.
    actor         VARCHAR(128) NOT NULL,
    -- What: REFUND_INITIATED, PAYMENT_CANCELLED, CASH_RECORDED, ADD_PAYMENT, etc.
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64)  NOT NULL,  -- PAYMENT / REFUND / BOOKING
    resource_id   VARCHAR(64)  NOT NULL,  -- transactionId / refundId / bookingRef
    amount        NUMERIC(12,2),
    currency      VARCHAR(8),
    binge_id      BIGINT,
    -- Free-form JSON for request/response context (reason, method, etc.)
    metadata      TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_resource  ON audit_log (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor     ON audit_log (actor);
CREATE INDEX IF NOT EXISTS idx_audit_log_created   ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_binge     ON audit_log (binge_id);
