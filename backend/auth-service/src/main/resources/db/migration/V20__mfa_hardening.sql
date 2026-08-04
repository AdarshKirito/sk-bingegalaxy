-- V20 — MFA (TOTP) hardening.
--
-- Three defects this addresses:
--   1. The TOTP shared secret was stored in PLAINTEXT. A read of this table (DB
--      dump, backup, SQL injection, over-broad support query) let an attacker
--      mint valid 2FA codes for every enrolled admin, undetectably and forever.
--      Secrets are now AES-256-GCM encrypted (see SecretCipher).
--   2. TOTP verification had NO rate limit. A 6-digit code with a +/-1 step
--      window means 3 of 1,000,000 codes are valid at any instant — trivially
--      brute-forceable given unlimited attempts.
--   3. Recovery codes were generated server-side but ECHOED BACK BY THE CLIENT
--      for storage, so whatever the client sent became the stored codes.

-- 1. Widen mfa_secret for ciphertext.
--    A 20-byte secret becomes 12-byte IV + 20-byte ciphertext + 16-byte tag = 48
--    bytes, base64 = 64 chars, plus the 'gcm:v1:' prefix = 71. The old VARCHAR(64)
--    would silently truncate and corrupt every newly enrolled secret.
--    Existing rows stay plaintext and are re-encrypted on next write (SecretCipher
--    reads unprefixed values as legacy plaintext).
ALTER TABLE users ALTER COLUMN mfa_secret TYPE VARCHAR(255);

-- 2. Per-account MFA throttling, mirroring the existing login lockout columns
--    (failed_login_attempts / locked_until) so operators have one mental model.
--    Kept separate from the login counters so a 2FA brute-force cannot lock the
--    password login, and vice versa.
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_failed_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_locked_until TIMESTAMP;

COMMENT ON COLUMN users.mfa_secret IS
    'AES-256-GCM ciphertext of the Base32 TOTP secret (gcm:v1: prefix). Unprefixed values are legacy plaintext.';
COMMENT ON COLUMN users.mfa_failed_attempts IS
    'Consecutive failed TOTP/recovery-code attempts; reset on success.';
COMMENT ON COLUMN users.mfa_locked_until IS
    'When set and in the future, MFA verification is refused (brute-force throttle).';
