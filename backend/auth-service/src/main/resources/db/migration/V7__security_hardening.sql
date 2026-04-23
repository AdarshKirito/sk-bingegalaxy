-- V7: Production-grade security hardening
--   * Auth audit log (who/what/when for every auth-sensitive event)
--   * Active session tracking (device/IP per refresh token, force-logout)
--   * Password history (prevent reuse of last N passwords)
--   * Email verification tokens (gate full activation)
--   * TOTP / MFA fields on users
--   * Tracks last password change for rotation policy
-- All new tables are additive; existing flows keep working with feature flags.

-- ─────────────────────────────────────────────────────────────
--  User hardening columns
-- ─────────────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enrolled_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_recovery_codes_hash TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_password_change_at TIMESTAMP;

-- Backfill: treat pre-existing accounts as verified to avoid locking out live users.
-- Fresh signups will have email_verified = false by default and go through verification.
UPDATE users SET email_verified = TRUE, email_verified_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE email_verified = FALSE;
UPDATE users SET last_password_change_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE last_password_change_at IS NULL;

-- ─────────────────────────────────────────────────────────────
--  Audit log
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,         -- e.g. LOGIN_SUCCESS, LOGIN_FAILED, PASSWORD_CHANGED, ADMIN_CREATED, MFA_ENABLED, ROLE_PROMOTED, SESSION_REVOKED
    actor_id        BIGINT,                        -- user who performed the action (nullable: e.g. failed login with unknown email)
    actor_role      VARCHAR(20),                   -- CUSTOMER / ADMIN / SUPER_ADMIN / ANONYMOUS
    target_id       BIGINT,                        -- user affected (may equal actor_id for self-actions)
    target_email    VARCHAR(150),                  -- denormalised for post-deletion visibility
    ip_address      VARCHAR(64),                   -- best-effort remote IP
    user_agent      VARCHAR(512),                  -- trimmed UA string
    request_id      VARCHAR(64),                   -- correlates with request tracing if available
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    failure_reason  VARCHAR(255),                  -- populated when success = false
    details         TEXT,                          -- JSON blob for extra context (old/new role, session id, etc.)
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_audit_created_at ON auth_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auth_audit_actor_id   ON auth_audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_auth_audit_target_id  ON auth_audit_log (target_id);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_type ON auth_audit_log (event_type);

-- ─────────────────────────────────────────────────────────────
--  Active sessions (tied to refresh token JTI)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_session (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    refresh_jti   VARCHAR(64)  NOT NULL UNIQUE,
    ip_address    VARCHAR(64),
    user_agent    VARCHAR(512),
    device_label  VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMP    NOT NULL,
    revoked_at    TIMESTAMP,
    revoked_by    BIGINT,
    revoke_reason VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_user_session_user_id    ON user_session (user_id);
CREATE INDEX IF NOT EXISTS idx_user_session_expires_at ON user_session (expires_at);
CREATE INDEX IF NOT EXISTS idx_user_session_active     ON user_session (user_id) WHERE revoked_at IS NULL;

-- ─────────────────────────────────────────────────────────────
--  Password history (prevent reuse of last N passwords)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS password_history (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_password_history_user_created ON password_history (user_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────
--  Email verification tokens
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_verification_token (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   VARCHAR(128) NOT NULL,
    otp          VARCHAR(12)  NOT NULL,
    otp_attempts INTEGER      NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP    NOT NULL,
    used         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_email_verification_token_hash ON email_verification_token (token_hash);
CREATE INDEX IF NOT EXISTS idx_email_verification_user_id          ON email_verification_token (user_id);
CREATE INDEX IF NOT EXISTS idx_email_verification_expires_at       ON email_verification_token (expires_at);
