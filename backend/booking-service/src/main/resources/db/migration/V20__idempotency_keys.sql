-- =============================================================================
-- V20: Stripe-style Idempotency-Key table for booking POST endpoints
--
--   • Key is supplied by the client on any state-changing booking call.
--   • (key, method, path, user_id) is the composite natural key — prevents
--     collisions when a client reuses a key across endpoints or users.
--   • request_hash = SHA-256(canonical payload); mismatch => 409 so a client
--     bug of reusing a key for a new operation surfaces instead of silently
--     running the new operation.
--   • Cached response is returned on exact replay until expires_at.
--   • Hourly scheduler (ShedLock) prunes expired rows.
-- =============================================================================

CREATE TABLE IF NOT EXISTS idempotency_key (
    idempotency_key VARCHAR(128) NOT NULL,
    http_method     VARCHAR(8)   NOT NULL,
    request_path    VARCHAR(255) NOT NULL,
    user_id         BIGINT,

    request_hash    VARCHAR(64)  NOT NULL,

    response_status INT          NOT NULL,
    response_body   TEXT,

    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL,

    PRIMARY KEY (idempotency_key, http_method, request_path, user_id)
);

CREATE INDEX IF NOT EXISTS idx_booking_idempotency_expires_at
    ON idempotency_key (expires_at);
