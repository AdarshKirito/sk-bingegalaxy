-- V12: OTP brute-force lockout fields.
--
-- Separate counter from failedLoginAttempts so a legitimate password mistype
-- does not consume the OTP budget and vice-versa.
--
-- Threshold + lock duration are application-level (see User.recordOtpFailure
-- and AuthService.OTP_LOCKOUT_*). Defaults: 5 failures -> 15 min lock.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS otp_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS otp_locked_until    TIMESTAMP NULL;

-- Lightweight index for housekeeping jobs that may sweep stale lockouts.
CREATE INDEX IF NOT EXISTS idx_users_otp_locked_until
    ON users(otp_locked_until)
    WHERE otp_locked_until IS NOT NULL;
