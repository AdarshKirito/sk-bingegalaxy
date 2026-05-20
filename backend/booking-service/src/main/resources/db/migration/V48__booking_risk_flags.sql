-- V48: Booking-level risk / abuse flags (Item 23 — fraud detection).
--
-- This is the operator inbox of "things to look at on this booking" — it is
-- distinct from `booking_event_log` (which is a state-transition audit log)
-- and from `customer_binge_freezes` (which is an enforcement record).
-- A flag NEVER blocks a booking on its own; it surfaces a signal so an admin
-- can decide whether to freeze the customer, cancel the booking, or escalate.
CREATE TABLE IF NOT EXISTS booking_risk_flags (
    id                          BIGSERIAL    PRIMARY KEY,
    booking_ref                 VARCHAR(30)  NOT NULL,
    binge_id                    BIGINT       NOT NULL,
    customer_id                 BIGINT       NOT NULL,
    rule_code                   VARCHAR(40)  NOT NULL,
    severity                    VARCHAR(10)  NOT NULL,
    source                      VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',
    reason                      TEXT,
    evidence                    TEXT,
    created_by_admin_id         BIGINT,
    acknowledged                BOOLEAN      NOT NULL DEFAULT FALSE,
    acknowledged_by_admin_id    BIGINT,
    acknowledged_at             TIMESTAMP,
    acknowledged_note           TEXT,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_risk_flag_booking  ON booking_risk_flags (booking_ref);
CREATE INDEX IF NOT EXISTS idx_risk_flag_customer ON booking_risk_flags (customer_id);
CREATE INDEX IF NOT EXISTS idx_risk_flag_open     ON booking_risk_flags (acknowledged, severity, created_at);
CREATE INDEX IF NOT EXISTS idx_risk_flag_binge    ON booking_risk_flags (binge_id, acknowledged, severity);
