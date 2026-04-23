-- Revoked JWT token registry for logout / force-revoke flows.
--
-- We store only the JTI (JWT ID claim) and the original token expiry.
-- Any access/refresh token whose JTI appears here is considered revoked
-- until its natural expiry, at which point the row can be purged by
-- the cleanup scheduler. Primary key is the JTI so double-revocation is
-- idempotent.
CREATE TABLE IF NOT EXISTS revoked_token (
    jti         VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT,
    token_type  VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    revoked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Drives the cleanup scheduler and makes the revocation check on the
-- refresh path O(log n) instead of a PK probe of a growing table.
CREATE INDEX IF NOT EXISTS idx_revoked_token_expires_at ON revoked_token (expires_at);
