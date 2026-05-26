-- V56: Venue room production lifecycle.
--
-- Adds room-level pricing, approval workflow, photo gallery, and a
-- per-binge "room selection required" toggle. Existing rooms are
-- grandfathered as APPROVED with price_addition = 0 so production
-- behaviour is unchanged.

ALTER TABLE venue_rooms
    ADD COLUMN IF NOT EXISTS price_addition NUMERIC(10, 2) NOT NULL DEFAULT 0;

ALTER TABLE venue_rooms
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE venue_rooms
    ADD COLUMN IF NOT EXISTS approval_decided_by BIGINT;

ALTER TABLE venue_rooms
    ADD COLUMN IF NOT EXISTS approval_decided_at TIMESTAMP;

ALTER TABLE venue_rooms
    ADD COLUMN IF NOT EXISTS approval_rejection_reason VARCHAR(500);

-- Grandfather any existing rooms.
UPDATE venue_rooms SET status = 'APPROVED' WHERE status IS NULL;

-- Constrain status to a known set so bad writes fail loudly.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_venue_rooms_status'
    ) THEN
        ALTER TABLE venue_rooms
            ADD CONSTRAINT chk_venue_rooms_status
            CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED'));
    END IF;
END $$;

-- price_addition must be non-negative (defensive against bad admin input).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_venue_rooms_price_nonneg'
    ) THEN
        ALTER TABLE venue_rooms
            ADD CONSTRAINT chk_venue_rooms_price_nonneg
            CHECK (price_addition >= 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_venue_rooms_status ON venue_rooms (status);

-- ── Photo gallery ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS venue_room_images (
    id           BIGSERIAL PRIMARY KEY,
    room_id      BIGINT NOT NULL REFERENCES venue_rooms(id) ON DELETE CASCADE,
    image_url    VARCHAR(1000) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_venue_room_images_room ON venue_room_images (room_id);

-- ── Booking-time snapshot ────────────────────────────────────
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS venue_room_price NUMERIC(10, 2) NOT NULL DEFAULT 0;

-- ── Per-binge toggle: when true, the customer must select a room. ─
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS room_selection_required BOOLEAN NOT NULL DEFAULT FALSE;
