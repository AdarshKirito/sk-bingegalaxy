-- V13: Authority Handover (delegated super-admin access + resource locks).
--
-- Industry-standard JIT (just-in-time) elevated access with audited grants and
-- per-record protection locks. See entities AuthorityGrant + ResourceLock for the
-- semantic model. Both tables are append-mostly: grants are NEVER physically
-- deleted (only soft-revoked) so audit trail is intact; locks are physically
-- deleted on release but each release event is captured in auth_audit_log.
--
-- ──────────────────────────────────────────────────────────────────────────
-- AUTHORITY GRANTS
-- ──────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS authority_grants (
    id                BIGSERIAL    PRIMARY KEY,
    grantee_user_id   BIGINT       NOT NULL,
    granted_by        BIGINT       NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    granted_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL,
    revoked_at        TIMESTAMP    NULL,
    revoked_by        BIGINT       NULL,
    revoke_reason     VARCHAR(500) NULL,
    -- ── invariants ──────────────────────────────────────────────
    CONSTRAINT chk_authority_grants_expiry_future
        CHECK (expires_at > granted_at),
    CONSTRAINT chk_authority_grants_revoke_consistency
        CHECK ((revoked_at IS NULL  AND revoked_by IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);

-- Hot lookup path: "is this user actively delegated?" runs on every JWT issue.
CREATE INDEX IF NOT EXISTS idx_authority_grants_grantee
    ON authority_grants(grantee_user_id);

CREATE INDEX IF NOT EXISTS idx_authority_grants_active
    ON authority_grants(grantee_user_id, expires_at, revoked_at)
    WHERE revoked_at IS NULL;

-- Side table for the @ElementCollection of scopes. One row per (grant, scope).
CREATE TABLE IF NOT EXISTS authority_grant_scopes (
    grant_id   BIGINT      NOT NULL,
    scope      VARCHAR(32) NOT NULL,
    PRIMARY KEY (grant_id, scope),
    CONSTRAINT fk_authority_grant_scopes_grant
        FOREIGN KEY (grant_id) REFERENCES authority_grants(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_authority_grant_scopes_scope
    ON authority_grant_scopes(scope);

-- ──────────────────────────────────────────────────────────────────────────
-- RESOURCE LOCKS
-- ──────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS resource_locks (
    id              BIGSERIAL     PRIMARY KEY,
    resource_type   VARCHAR(64)   NOT NULL,
    resource_id     VARCHAR(128)  NOT NULL,
    locked_by       BIGINT        NOT NULL,
    locked_by_name  VARCHAR(200)  NULL,
    reason          VARCHAR(500)  NOT NULL,
    locked_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resource_locks_type_id UNIQUE (resource_type, resource_id)
);

CREATE INDEX IF NOT EXISTS idx_resource_locks_owner
    ON resource_locks(locked_by);

CREATE INDEX IF NOT EXISTS idx_resource_locks_type
    ON resource_locks(resource_type);
