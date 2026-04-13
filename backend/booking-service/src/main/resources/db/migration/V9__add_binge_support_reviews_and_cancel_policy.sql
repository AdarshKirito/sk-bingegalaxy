ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS support_email VARCHAR(150),
    ADD COLUMN IF NOT EXISTS support_phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS support_whatsapp VARCHAR(20),
    ADD COLUMN IF NOT EXISTS customer_cancellation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS customer_cancellation_cutoff_minutes INTEGER NOT NULL DEFAULT 180;

CREATE TABLE IF NOT EXISTS booking_reviews (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    booking_id BIGINT NOT NULL REFERENCES bookings(id),
    booking_ref VARCHAR(20) NOT NULL,
    customer_id BIGINT NOT NULL,
    admin_id BIGINT,
    reviewer_role VARCHAR(20) NOT NULL,
    rating INTEGER,
    comment VARCHAR(1200),
    skipped BOOLEAN NOT NULL DEFAULT FALSE,
    visible_to_customer BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_booking_reviews_booking_ref ON booking_reviews(booking_ref);
CREATE INDEX IF NOT EXISTS idx_booking_reviews_customer ON booking_reviews(customer_id);
CREATE INDEX IF NOT EXISTS idx_booking_reviews_binge_created ON booking_reviews(binge_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_reviews_customer_once ON booking_reviews(booking_ref, customer_id, reviewer_role)
WHERE reviewer_role = 'CUSTOMER';
CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_reviews_admin_once ON booking_reviews(booking_ref, admin_id, reviewer_role)
WHERE reviewer_role = 'ADMIN';
