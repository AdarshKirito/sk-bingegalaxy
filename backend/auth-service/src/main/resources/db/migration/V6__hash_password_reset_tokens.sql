-- V6: switch password_reset_tokens.token from plaintext UUID to SHA-256 hash.
-- Application now stores only the hash; the plaintext token is sent to the
-- user via email/SMS and never persisted. This protects unredeemed reset
-- tokens in the event of a database breach.
--
-- Existing unredeemed tokens were stored as plaintext UUIDs and are
-- incompatible with the new hashing scheme — invalidate them so users must
-- request a fresh reset link.
UPDATE password_reset_tokens SET used = TRUE WHERE used = FALSE;

-- 64-char hex output of SHA-256 fits comfortably; widen if column was narrower.
ALTER TABLE password_reset_tokens ALTER COLUMN token TYPE VARCHAR(128);
