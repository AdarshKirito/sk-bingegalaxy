-- V30 — Customer-binge freeze policy + cancellation refund flags
--
-- Adds binge-level cancellation policy fields and a customer_binge_freezes
-- table that tracks temporary booking-flow freezes triggered by repeated
-- pending cancellations / payment timeouts (or applied manually by an admin).

-- ── Binge-level policy fields ────────────────────────────────────────────────
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS freeze_duration_minutes INT NOT NULL DEFAULT 60;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS max_pending_cancels_before_freeze INT NOT NULL DEFAULT 3;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS max_pending_payment_timeouts_before_freeze INT NOT NULL DEFAULT 3;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS refund_on_successful_payment_cancel BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS refund_on_pending_payment_cancel BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS freeze_policy_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- ── customer_binge_freezes ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS customer_binge_freezes (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    binge_id        BIGINT       NOT NULL,
    freeze_until    TIMESTAMP    NOT NULL,
    reason          TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    trigger_type    VARCHAR(40)  NOT NULL,
    triggered_by_user_id BIGINT,
    lifted_by_user_id BIGINT,
    lifted_at       TIMESTAMP,
    lifted_reason   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_freeze_status   CHECK (status IN ('ACTIVE','LIFTED','EXPIRED')),
    CONSTRAINT chk_freeze_trigger  CHECK (trigger_type IN ('CUSTOMER_CANCELLATIONS','PAYMENT_TIMEOUTS','MANUAL'))
);

CREATE INDEX IF NOT EXISTS idx_freeze_customer_binge_status
    ON customer_binge_freezes (customer_id, binge_id, status);

CREATE INDEX IF NOT EXISTS idx_freeze_binge_status
    ON customer_binge_freezes (binge_id, status, freeze_until);

CREATE INDEX IF NOT EXISTS idx_freeze_until
    ON customer_binge_freezes (freeze_until)
    WHERE status = 'ACTIVE';
