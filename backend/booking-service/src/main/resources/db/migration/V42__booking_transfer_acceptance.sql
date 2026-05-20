-- V42: Booking-transfer 2-phase acceptance flow.
--
-- Today the /transfer endpoint flips ownership in a single step. Production
-- grade requires the recipient to consent. This migration introduces the
-- BookingTransfer aggregate that tracks an offer through PENDING → ACCEPTED /
-- DECLINED / EXPIRED / REVOKED, with an opaque accept-token that the recipient
-- clicks from an email and an expiry timer enforced by a scheduler.
--
-- Anti-abuse: per-customer counter is computed at runtime via
-- created_by_customer_id + created_at, no extra column needed.

CREATE TABLE IF NOT EXISTS booking_transfers (
    id                              BIGSERIAL PRIMARY KEY,
    booking_ref                     VARCHAR(20)  NOT NULL,
    binge_id                        BIGINT       NOT NULL,

    from_customer_id                BIGINT       NOT NULL,
    from_customer_name              VARCHAR(150) NOT NULL,
    from_customer_email             VARCHAR(150) NOT NULL,

    to_name                         VARCHAR(150) NOT NULL,
    to_email                        VARCHAR(150) NOT NULL,
    to_phone                        VARCHAR(20),
    to_phone_country_code           VARCHAR(8),
    -- populated on accept if the recipient is signed in
    to_customer_id                  BIGINT,

    status                          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    -- 64-char URL-safe token (base64url of 32 random bytes).
    -- Single-use bearer; the recipient clicks an email link containing this.
    accept_token                    VARCHAR(80)  NOT NULL UNIQUE,

    expires_at                      TIMESTAMP    NOT NULL,
    created_at                      TIMESTAMP    NOT NULL DEFAULT NOW(),
    accepted_at                     TIMESTAMP,
    declined_at                     TIMESTAMP,
    revoked_at                      TIMESTAMP,
    decline_reason                  VARCHAR(500),

    CONSTRAINT chk_transfer_status
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','REVOKED'))
);

-- Hot paths
CREATE INDEX IF NOT EXISTS idx_booking_transfers_booking_ref
    ON booking_transfers (booking_ref);
CREATE INDEX IF NOT EXISTS idx_booking_transfers_from_customer_created
    ON booking_transfers (from_customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_booking_transfers_status_expires
    ON booking_transfers (status, expires_at);

-- Only one PENDING transfer per booking at a time. A revoked/declined/expired
-- row does NOT block re-issuing — that's the second phase of the lifecycle.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_booking_transfers_pending
    ON booking_transfers (booking_ref) WHERE status = 'PENDING';
