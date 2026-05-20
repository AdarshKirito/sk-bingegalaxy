-- V49: Threaded support notes + cancel reason + escalation + goodwill
-- (Item 24 — production-grade support console).

-- ── Threaded notes table (replaces flat Booking.adminNotes concatenation) ─
CREATE TABLE IF NOT EXISTS booking_notes (
    id                  BIGSERIAL    PRIMARY KEY,
    booking_ref         VARCHAR(30)  NOT NULL,
    binge_id            BIGINT       NOT NULL,
    author_admin_id     BIGINT       NOT NULL,
    author_name         VARCHAR(100) NOT NULL,
    body                TEXT         NOT NULL,
    visibility          VARCHAR(12)  NOT NULL DEFAULT 'INTERNAL',
    pinned              BOOLEAN      NOT NULL DEFAULT FALSE,
    edited              BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_booking_notes_ref     ON booking_notes (booking_ref, created_at);
CREATE INDEX IF NOT EXISTS idx_booking_notes_pinned  ON booking_notes (booking_ref, pinned, created_at);

-- ── New booking-level support fields ──────────────────────────────────────
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS cancellation_reason         VARCHAR(500),
    ADD COLUMN IF NOT EXISTS escalation_level            VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS escalation_reason           VARCHAR(500),
    ADD COLUMN IF NOT EXISTS goodwill_credit             NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS goodwill_reason             VARCHAR(500),
    ADD COLUMN IF NOT EXISTS goodwill_issued_by_admin_id BIGINT,
    ADD COLUMN IF NOT EXISTS goodwill_issued_at          TIMESTAMP;

-- One-shot backfill: copy any existing flat adminNotes contents into the new
-- threaded store as an "imported legacy" note so operators don't lose them.
-- Body is the raw " | "-separated string; admins can split / re-pin manually.
INSERT INTO booking_notes (booking_ref, binge_id, author_admin_id, author_name, body, visibility, pinned, created_at, updated_at)
SELECT b.booking_ref, b.binge_id, 0, 'Legacy import', b.admin_notes, 'INTERNAL', FALSE, b.created_at, CURRENT_TIMESTAMP
FROM bookings b
WHERE b.admin_notes IS NOT NULL AND TRIM(b.admin_notes) <> ''
  AND NOT EXISTS (SELECT 1 FROM booking_notes n WHERE n.booking_ref = b.booking_ref);
