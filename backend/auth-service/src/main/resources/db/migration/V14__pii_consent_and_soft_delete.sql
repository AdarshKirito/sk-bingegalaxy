-- V14: DPDP (India's Digital Personal Data Protection Act) and GDPR-style
-- soft-delete, anonymization, and consent tracking.
--
-- Right to erasure: users can request account deletion; PII is anonymized
-- (not hard-deleted, because booking and payment records reference the user_id).
-- Consent recording: captures when consent was given and to which purposes.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deleted_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS anonymized_at       TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS consent_given_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS consent_marketing   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS data_retention_expires_at TIMESTAMP;

-- Index for the anonymization scheduler: find pending deletion requests older
-- than the retention grace period (default 30 days after deletion_requested_at).
CREATE INDEX IF NOT EXISTS idx_users_deletion_requested
    ON users (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL AND anonymized_at IS NULL;

-- Index to exclude soft-deleted users from normal auth queries efficiently.
CREATE INDEX IF NOT EXISTS idx_users_active
    ON users (email)
    WHERE deleted_at IS NULL;

-- Populate consent_given_at for existing users (retroactive — they accepted
-- the terms at registration). In production, set to their created_at timestamp.
UPDATE users SET consent_given_at = created_at WHERE consent_given_at IS NULL;
