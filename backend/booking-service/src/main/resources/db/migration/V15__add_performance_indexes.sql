-- Composite index for slot-availability queries (bingeId + bookingDate)
CREATE INDEX IF NOT EXISTS idx_booking_binge_date
    ON bookings (binge_id, booking_date);

-- Status index for dashboard filtering and reconciliation
CREATE INDEX IF NOT EXISTS idx_booking_status
    ON bookings (status);

-- Composite for customer + status queries (my-bookings pages)
CREATE INDEX IF NOT EXISTS idx_booking_customer_status
    ON bookings (customer_id, status);
