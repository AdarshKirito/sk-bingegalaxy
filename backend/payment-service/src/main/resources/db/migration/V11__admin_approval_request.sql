-- V11: Maker-checker (4-eyes) approval workflow.
-- Risky payment-service actions (large refund retries, manual cash recording
-- above threshold, payment cancellations after settlement) must be requested
-- by one admin and approved by a different admin before executing.

CREATE TABLE IF NOT EXISTS admin_approval_request (
    id                BIGSERIAL PRIMARY KEY,
    action_type       VARCHAR(60)  NOT NULL,
    resource_type     VARCHAR(60)  NOT NULL,
    resource_id       VARCHAR(120) NOT NULL,
    payload           JSONB,
    amount            DECIMAL(15, 2),
    currency          VARCHAR(8),
    binge_id          BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_by      VARCHAR(160) NOT NULL,
    requested_by_id   BIGINT,
    requested_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_reason    VARCHAR(1000),
    reviewed_by       VARCHAR(160),
    reviewed_by_id    BIGINT,
    reviewed_at       TIMESTAMP,
    review_reason     VARCHAR(1000),
    executed_at       TIMESTAMP,
    executed_result   VARCHAR(2000),
    expires_at        TIMESTAMP    NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_aar_status
        CHECK (status IN ('PENDING','APPROVED','REJECTED','EXECUTED','CANCELLED','EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_aar_status_requested_at
    ON admin_approval_request(status, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_aar_resource
    ON admin_approval_request(resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_aar_binge
    ON admin_approval_request(binge_id);
CREATE INDEX IF NOT EXISTS idx_aar_action_type
    ON admin_approval_request(action_type);
