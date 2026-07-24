-- Extend the admin notification inbox into a two-way messaging system.
-- Existing rows are system notifications: sender_role defaults to 'SYSTEM', sender_* null.
-- User messages carry an authoritative sender (stamped from the gateway) plus a thread id
-- (root message id) and parent id so replies group into conversations.
ALTER TABLE admin_notifications
    ADD COLUMN IF NOT EXISTS sender_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS sender_role    VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN IF NOT EXISTS sender_name    VARCHAR(150),
    ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(150),
    ADD COLUMN IF NOT EXISTS thread_id      BIGINT,
    ADD COLUMN IF NOT EXISTS parent_id      BIGINT;

CREATE INDEX IF NOT EXISTS idx_admin_notifications_thread
    ON admin_notifications(thread_id, created_at);
CREATE INDEX IF NOT EXISTS idx_admin_notifications_sender
    ON admin_notifications(sender_user_id, created_at DESC);
