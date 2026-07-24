-- ═════════════════════════════════════════════════════════════════════════
-- Per-binge module permission matrix + audit trail.
--
-- Model (deny-list): NO row for (binge,user,module,action) = allowed — the
-- platform behaves exactly as before until a SUPER_ADMIN restricts something.
-- A row records an explicit decision:
--   enabled=false                → the module/action is disabled for that user
--   locked_by_super_admin=true   → nobody below SUPER_ADMIN may re-enable it,
--                                  now or for any future sub-user (Venue
--                                  Manager / Staff) of this binge.
--
-- action_key 'ALL' covers the whole module; finer actions (VIEW/CREATE/EDIT/
-- DELETE/MANAGE…) use the same table — the schema is action-ready even though
-- today's UI manages module-level rows only.
-- ═════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS binge_module_permissions (
    id                     BIGSERIAL PRIMARY KEY,
    binge_id               BIGINT      NOT NULL,
    user_id                BIGINT      NOT NULL,
    role                   VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    module_key             VARCHAR(40) NOT NULL,
    action_key             VARCHAR(20) NOT NULL DEFAULT 'ALL',
    enabled                BOOLEAN     NOT NULL DEFAULT TRUE,
    locked_by_super_admin  BOOLEAN     NOT NULL DEFAULT FALSE,
    granted_by_user_id     BIGINT,
    granted_by_role        VARCHAR(32),
    remarks                VARCHAR(500),
    created_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bmp_scope UNIQUE (binge_id, user_id, module_key, action_key)
);
CREATE INDEX IF NOT EXISTS idx_bmp_binge_user ON binge_module_permissions (binge_id, user_id);

-- Immutable audit of every permission change (who changed whose access).
CREATE TABLE IF NOT EXISTS binge_permission_audit (
    id                BIGSERIAL PRIMARY KEY,
    binge_id          BIGINT      NOT NULL,
    target_user_id    BIGINT      NOT NULL,
    module_key        VARCHAR(40) NOT NULL,
    action_key        VARCHAR(20) NOT NULL DEFAULT 'ALL',
    old_enabled       BOOLEAN,
    new_enabled       BOOLEAN     NOT NULL,
    old_locked        BOOLEAN,
    new_locked        BOOLEAN     NOT NULL,
    changed_by_user_id BIGINT     NOT NULL,
    changed_by_role   VARCHAR(32) NOT NULL,
    remarks           VARCHAR(500),
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bpa_binge ON binge_permission_audit (binge_id, created_at DESC);

-- Free-text access/ops remarks shown on the binge About page (super-admin edits).
ALTER TABLE binges ADD COLUMN IF NOT EXISTS access_remarks VARCHAR(1000);
