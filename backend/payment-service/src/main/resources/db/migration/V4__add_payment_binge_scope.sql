ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS binge_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_payment_binge_id ON payments (binge_id);
CREATE INDEX IF NOT EXISTS idx_payment_booking_ref_binge_id ON payments (booking_ref, binge_id);
CREATE INDEX IF NOT EXISTS idx_payment_customer_id_binge_id ON payments (customer_id, binge_id);