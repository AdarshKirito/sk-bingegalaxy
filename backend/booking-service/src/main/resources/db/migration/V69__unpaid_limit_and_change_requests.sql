-- P1: admin-configurable cap on concurrent unpaid (PENDING) bookings per customer per binge.
-- Replaces the global hardcoded max-pending-per-customer=2 as the effective source of truth;
-- 2 keeps existing behaviour for every venue until an admin tunes it.
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS max_unpaid_bookings_per_customer INT NOT NULL DEFAULT 2;

-- P4: full approve/reject state machine + audit trail for binge change requests
-- (currently only COUNTRY_CHANGE, but typed so future gated fields reuse the table).
CREATE TABLE IF NOT EXISTS binge_change_requests (
    id                     BIGSERIAL PRIMARY KEY,
    binge_id               BIGINT       NOT NULL,
    request_type           VARCHAR(40)  NOT NULL DEFAULT 'COUNTRY_CHANGE',
    current_value          VARCHAR(100),
    requested_value        VARCHAR(100) NOT NULL,
    requested_currency     VARCHAR(3),
    reason                 VARCHAR(500),
    requested_by_admin_id  BIGINT       NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by_user_id     BIGINT,
    decided_at             TIMESTAMP,
    decision_note          VARCHAR(500),
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bcr_status ON binge_change_requests (status);
CREATE INDEX IF NOT EXISTS idx_bcr_binge ON binge_change_requests (binge_id);
CREATE INDEX IF NOT EXISTS idx_bcr_requester ON binge_change_requests (requested_by_admin_id);
