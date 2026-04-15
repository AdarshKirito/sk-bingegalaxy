-- Capacity management: add max concurrent bookings to binge
ALTER TABLE binges
    ADD COLUMN IF NOT EXISTS max_concurrent_bookings INTEGER;

-- Waitlist entries table
CREATE TABLE IF NOT EXISTS waitlist_entries (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_name VARCHAR(150) NOT NULL,
    customer_email VARCHAR(150) NOT NULL,
    customer_phone VARCHAR(15),
    event_type_id BIGINT NOT NULL REFERENCES event_types(id),
    preferred_date DATE NOT NULL,
    preferred_start_time TIME NOT NULL,
    duration_minutes INTEGER NOT NULL,
    number_of_guests INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    position INTEGER NOT NULL,
    offer_expires_at TIMESTAMP,
    notified_at TIMESTAMP,
    converted_booking_ref VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_waitlist_binge_date ON waitlist_entries(binge_id, preferred_date);
CREATE INDEX IF NOT EXISTS idx_waitlist_customer ON waitlist_entries(customer_id);
CREATE INDEX IF NOT EXISTS idx_waitlist_status ON waitlist_entries(status);

-- Cancellation refund tiers table
CREATE TABLE IF NOT EXISTS cancellation_tiers (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT NOT NULL,
    hours_before_start INTEGER NOT NULL,
    refund_percentage INTEGER NOT NULL,
    label VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cancel_tier_binge ON cancellation_tiers(binge_id);
