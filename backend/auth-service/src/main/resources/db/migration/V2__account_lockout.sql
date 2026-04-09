-- Account lockout fields for brute-force protection
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;

-- OTP brute-force protection
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS otp_attempts INTEGER DEFAULT 0;
