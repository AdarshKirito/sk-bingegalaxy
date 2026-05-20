-- V10: Pending email-change flow
--   * Stores the new email + OTP + hashed token until the user verifies the
--     new address. Only after verification is the email column updated.
--   * No existing data is touched — all columns default to NULL.

ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email           VARCHAR(150);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_otp       VARCHAR(8);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_token_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email_expires_at TIMESTAMP;
