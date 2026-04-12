-- Add missing indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_booking_status ON bookings (status);
CREATE INDEX IF NOT EXISTS idx_cpp_customer_id ON customer_pricing_profiles (customer_id);
