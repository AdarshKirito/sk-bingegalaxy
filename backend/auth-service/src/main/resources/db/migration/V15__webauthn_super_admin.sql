-- V15: FIDO2 / WebAuthn hardware security key enforcement for SUPER_ADMIN accounts.
--
-- TOTP (time-based OTP) can be phished in real-time by a man-in-the-browser proxy
-- and bypassed via SIM-swap on phone-based recovery. SUPER_ADMIN accounts have
-- access to maker-checker approvals, refund overrides, and the authority delegation
-- system — a compromised SUPER_ADMIN is a full platform compromise.
--
-- Hardware security keys (FIDO2/WebAuthn) are phishing-resistant by cryptographic
-- design: the key signs a challenge that includes the origin domain, making
-- real-time proxying impossible.
--
-- This migration adds the fields needed for WebAuthn credential storage.
-- The actual WebAuthn registration/authentication ceremony is implemented in the
-- application layer using the webauthn4j library (or Yubico java-webauthn-server).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS webauthn_credential_id  TEXT,   -- Base64url-encoded credential ID from authenticator
    ADD COLUMN IF NOT EXISTS webauthn_public_key_cose TEXT,  -- COSE-encoded public key (CBOR, stored as base64url)
    ADD COLUMN IF NOT EXISTS webauthn_enrolled_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS webauthn_last_used_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS webauthn_aaguid         VARCHAR(64);  -- Authenticator AAGUID for device identification

-- Index for quick credential lookup during authentication ceremony
CREATE INDEX IF NOT EXISTS idx_users_webauthn_credential
    ON users (webauthn_credential_id)
    WHERE webauthn_credential_id IS NOT NULL;

-- Comment: webauthn_enrolled_at IS NOT NULL means WebAuthn is active for this user.
-- SUPER_ADMIN login is blocked at the application layer if webauthn_enrolled_at IS NULL.
