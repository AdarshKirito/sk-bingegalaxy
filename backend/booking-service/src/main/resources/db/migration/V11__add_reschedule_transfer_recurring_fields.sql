-- V11: Add reschedule, transfer, and recurring booking fields to bookings table

ALTER TABLE bookings ADD COLUMN reschedule_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN original_booking_ref VARCHAR(20);
ALTER TABLE bookings ADD COLUMN transferred BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bookings ADD COLUMN original_customer_id BIGINT;
ALTER TABLE bookings ADD COLUMN original_customer_name VARCHAR(150);
ALTER TABLE bookings ADD COLUMN recurring_group_id VARCHAR(40);

-- Index for recurring group lookups
CREATE INDEX idx_bookings_recurring_group_id ON bookings (recurring_group_id) WHERE recurring_group_id IS NOT NULL;

-- Index for transferred booking queries
CREATE INDEX idx_bookings_transferred ON bookings (transferred) WHERE transferred = TRUE;

-- Index for original customer tracking
CREATE INDEX idx_bookings_original_customer_id ON bookings (original_customer_id) WHERE original_customer_id IS NOT NULL;
