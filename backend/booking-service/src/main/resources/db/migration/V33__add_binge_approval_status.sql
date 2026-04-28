-- V33: Binge approval workflow.
--
-- Adds an approval lifecycle for newly-created binges. Binges created by a
-- regular ADMIN now start as PENDING_APPROVAL and only become customer-visible
-- once a SUPER_ADMIN approves them. SUPER_ADMIN-created binges are auto-APPROVED.
-- Existing rows are grandfathered as APPROVED so production behavior is preserved.
--
-- Status values: PENDING_APPROVAL, APPROVED, REJECTED.

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS approval_decided_by BIGINT;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS approval_decided_at TIMESTAMP;

ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS approval_rejection_reason VARCHAR(500);

-- Grandfather: every existing row is APPROVED.
UPDATE binges SET status = 'APPROVED' WHERE status IS NULL;

CREATE INDEX IF NOT EXISTS idx_binges_status ON binges (status);
