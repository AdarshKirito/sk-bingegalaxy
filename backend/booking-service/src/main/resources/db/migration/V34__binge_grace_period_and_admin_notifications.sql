-- V34: Binge approval grace-period enforcement + admin notifications inbox.
--
-- WHY ----------------------------------------------------------------------
-- After a SUPER_ADMIN approves a new binge, the requesting ADMIN has 24 hours
-- to create at least one active event type. If they don't, the scheduler
-- auto-deactivates the binge and notifies super-admins. Mid-way (12h) we send
-- a courtesy warning so legitimate admins aren't surprised.
--
-- We persist:
--   * binges.first_event_created_at        -- timestamp of first active event
--   * binges.grace_warning_sent_at         -- prevents duplicate warnings
--   * binges.auto_deactivated_at           -- audit + UI banner trigger
--
-- We also introduce an in-app notifications inbox for admins / super-admins so
-- approval state changes, grace-period warnings, and auto-deactivation are
-- surfaced without depending on email delivery.

-- ── Binge grace-period bookkeeping ─────────────────────────────────────────
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS first_event_created_at TIMESTAMP;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS grace_warning_sent_at TIMESTAMP;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS auto_deactivated_at TIMESTAMP;

-- Backfill: any existing binge that already has at least one event_type rows
-- is considered "operational" and exempt from the grace-period sweep.
UPDATE binges b
SET first_event_created_at = COALESCE(b.first_event_created_at, b.created_at)
WHERE EXISTS (SELECT 1 FROM event_types e WHERE e.binge_id = b.id);

-- ── Admin notifications inbox ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_notifications (
    id                 BIGSERIAL PRIMARY KEY,
    recipient_user_id  BIGINT,                   -- NULL = role-wide broadcast
    recipient_role     VARCHAR(32)  NOT NULL,    -- ADMIN | SUPER_ADMIN
    type               VARCHAR(64)  NOT NULL,    -- e.g. BINGE_APPROVED, BINGE_GRACE_WARNING
    severity           VARCHAR(16)  NOT NULL DEFAULT 'INFO', -- INFO | WARNING | CRITICAL
    title              VARCHAR(200) NOT NULL,
    message            VARCHAR(1000) NOT NULL,
    related_binge_id   BIGINT,
    action_url         VARCHAR(500),
    read_at            TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_notif_recipient_unread
    ON admin_notifications (recipient_user_id, read_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_notif_role
    ON admin_notifications (recipient_role, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_notif_binge
    ON admin_notifications (related_binge_id);
