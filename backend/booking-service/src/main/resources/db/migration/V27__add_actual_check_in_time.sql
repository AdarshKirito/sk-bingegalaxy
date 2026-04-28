-- V27: Add actual check-in timestamp for accurate session-duration tracking
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS actual_check_in_time TIMESTAMP;
