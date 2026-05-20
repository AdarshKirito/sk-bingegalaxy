-- V44: Check-in tokens (QR + OTP) with single-use semantics and TTL.
--
-- One row per issued token. Tokens are scoped to a single booking.
-- For QR: token is a 32-char URL-safe random. Stored in plain (only valid
--          if not consumed and not expired).
-- For OTP: a 6-digit numeric code; we store ONLY the SHA-256 hash so that
--          a DB leak does not expose live OTPs. Verification re-hashes the
--          submitted code.
-- consumed_at is set on first successful verify; subsequent verifies fail
-- (replay protection). Failed-attempt counter prevents brute-forcing OTPs.

CREATE TABLE IF NOT EXISTS check_in_tokens (
    id                BIGSERIAL PRIMARY KEY,
    booking_ref       VARCHAR(20)  NOT NULL,
    booking_id        BIGINT       NOT NULL,
    token_type        VARCHAR(8)   NOT NULL,                -- QR | OTP
    token_value       VARCHAR(128) NOT NULL,                -- raw QR token, or sha256(otp)
    issued_by         VARCHAR(150),                         -- admin email; null for customer-self-issue
    issued_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL,
    consumed_at       TIMESTAMP,
    consumed_by       VARCHAR(150),
    consumed_ip       VARCHAR(64),
    failed_attempts   INTEGER      NOT NULL DEFAULT 0,
    binge_id          BIGINT,
    CONSTRAINT chk_token_type CHECK (token_type IN ('QR','OTP'))
);

CREATE INDEX IF NOT EXISTS idx_check_in_tokens_booking_ref
    ON check_in_tokens (booking_ref);

CREATE INDEX IF NOT EXISTS idx_check_in_tokens_value_type
    ON check_in_tokens (token_value, token_type);

CREATE INDEX IF NOT EXISTS idx_check_in_tokens_expires
    ON check_in_tokens (expires_at)
    WHERE consumed_at IS NULL;
