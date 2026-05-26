-- V57: Room maintenance blocks.
--
-- Lets admins reserve a time window on a specific venue room
-- (e.g. cleaning, repairs, private hold) without disabling the
-- whole room. Any booking whose slot overlaps an active block
-- is rejected by BookingService.countRoomBookings (block fills
-- the room to capacity for the overlapping window).

CREATE TABLE IF NOT EXISTS room_blocks (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES venue_rooms(id) ON DELETE CASCADE,
    start_at    TIMESTAMP   NOT NULL,
    end_at      TIMESTAMP   NOT NULL,
    reason      VARCHAR(500),
    created_by  BIGINT,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT chk_room_blocks_window CHECK (end_at > start_at)
);

CREATE INDEX IF NOT EXISTS idx_room_blocks_room_window
    ON room_blocks (room_id, start_at, end_at);
