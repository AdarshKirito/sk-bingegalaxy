CREATE TABLE IF NOT EXISTS binges (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    address VARCHAR(500),
    admin_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    operational_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_types (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    base_price NUMERIC(10, 2) NOT NULL,
    hourly_rate NUMERIC(10, 2) NOT NULL,
    price_per_guest NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    min_hours INTEGER NOT NULL DEFAULT 1,
    max_hours INTEGER NOT NULL DEFAULT 8,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS event_type_images (
    event_type_id BIGINT NOT NULL REFERENCES event_types (id),
    image_url TEXT
);

CREATE INDEX IF NOT EXISTS idx_event_type_images_event_type_id ON event_type_images (event_type_id);

CREATE TABLE IF NOT EXISTS add_ons (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    price NUMERIC(10, 2) NOT NULL,
    category VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS add_on_images (
    add_on_id BIGINT NOT NULL REFERENCES add_ons (id),
    image_url VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_add_on_images_add_on_id ON add_on_images (add_on_id);

CREATE TABLE IF NOT EXISTS rate_codes (
    id BIGSERIAL PRIMARY KEY,
    binge_id BIGINT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS customer_pricing_profiles (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    binge_id BIGINT,
    rate_code_id BIGINT REFERENCES rate_codes (id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bookings (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(20) NOT NULL,
    binge_id BIGINT,
    customer_id BIGINT NOT NULL,
    customer_name VARCHAR(150) NOT NULL,
    customer_email VARCHAR(150) NOT NULL,
    customer_phone VARCHAR(15) NOT NULL,
    event_type_id BIGINT NOT NULL REFERENCES event_types (id),
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    duration_hours INTEGER NOT NULL,
    duration_minutes INTEGER,
    special_notes VARCHAR(1000),
    admin_notes VARCHAR(1000),
    base_amount NUMERIC(10, 2) NOT NULL,
    add_on_amount NUMERIC(10, 2) NOT NULL,
    guest_amount NUMERIC(10, 2) NOT NULL,
    total_amount NUMERIC(10, 2) NOT NULL,
    collected_amount NUMERIC(10, 2),
    number_of_guests INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    payment_method VARCHAR(30),
    checked_in BOOLEAN NOT NULL DEFAULT FALSE,
    actual_checkout_time TIMESTAMP,
    actual_used_minutes INTEGER,
    early_checkout_note VARCHAR(500),
    pricing_source VARCHAR(30),
    rate_code_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_booking_ref ON bookings (booking_ref);
CREATE INDEX IF NOT EXISTS idx_booking_customer ON bookings (customer_id);
CREATE INDEX IF NOT EXISTS idx_booking_date ON bookings (booking_date);

CREATE TABLE IF NOT EXISTS booking_add_ons (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings (id),
    add_on_id BIGINT NOT NULL REFERENCES add_ons (id),
    quantity INTEGER NOT NULL DEFAULT 1,
    price NUMERIC(10, 2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_booking_add_ons_booking_id ON booking_add_ons (booking_id);
CREATE INDEX IF NOT EXISTS idx_booking_add_ons_add_on_id ON booking_add_ons (add_on_id);

CREATE TABLE IF NOT EXISTS booking_event_log (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    triggered_by BIGINT,
    triggered_by_role VARCHAR(20),
    description VARCHAR(2000),
    snapshot JSONB,
    event_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bel_booking_ref ON booking_event_log (booking_ref);
CREATE INDEX IF NOT EXISTS idx_bel_event_type ON booking_event_log (event_type);
CREATE INDEX IF NOT EXISTS idx_bel_created_at ON booking_event_log (created_at);

CREATE TABLE IF NOT EXISTS booking_read_model (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(20) NOT NULL,
    customer_id BIGINT,
    status VARCHAR(255),
    payment_status VARCHAR(255),
    total_amount NUMERIC(10, 2),
    collected_amount NUMERIC(10, 2),
    booking_date DATE,
    start_time TIME,
    duration_minutes INTEGER,
    number_of_guests INTEGER NOT NULL DEFAULT 0,
    checked_in BOOLEAN NOT NULL DEFAULT FALSE,
    event_type_id BIGINT,
    event_count INTEGER NOT NULL DEFAULT 0,
    last_event_id BIGINT,
    projected_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_brm_booking_ref ON booking_read_model (booking_ref);
CREATE INDEX IF NOT EXISTS idx_brm_status ON booking_read_model (status);
CREATE INDEX IF NOT EXISTS idx_brm_customer ON booking_read_model (customer_id);
CREATE INDEX IF NOT EXISTS idx_brm_booking_date ON booking_read_model (booking_date);

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(100) NOT NULL,
    aggregate_key VARCHAR(30) NOT NULL,
    payload TEXT NOT NULL,
    sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_sent ON outbox_event (sent, created_at);

CREATE TABLE IF NOT EXISTS processed_event (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_pe_event_key ON processed_event (event_key);

CREATE TABLE IF NOT EXISTS saga_state (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(20) NOT NULL,
    saga_status VARCHAR(30) NOT NULL DEFAULT 'STARTED',
    last_completed_step VARCHAR(50),
    failure_reason VARCHAR(500),
    compensation_attempts INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_saga_booking_ref ON saga_state (booking_ref);
CREATE INDEX IF NOT EXISTS idx_saga_status ON saga_state (saga_status);

CREATE TABLE IF NOT EXISTS system_settings (
    id BIGINT PRIMARY KEY,
    operational_date DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS rate_code_event_pricing (
    id BIGSERIAL PRIMARY KEY,
    rate_code_id BIGINT NOT NULL REFERENCES rate_codes (id),
    event_type_id BIGINT NOT NULL REFERENCES event_types (id),
    base_price NUMERIC(10, 2) NOT NULL,
    hourly_rate NUMERIC(10, 2) NOT NULL,
    price_per_guest NUMERIC(10, 2) NOT NULL DEFAULT 0.00
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rate_code_event_pricing_pair ON rate_code_event_pricing (rate_code_id, event_type_id);

CREATE TABLE IF NOT EXISTS rate_code_addon_pricing (
    id BIGSERIAL PRIMARY KEY,
    rate_code_id BIGINT NOT NULL REFERENCES rate_codes (id),
    add_on_id BIGINT NOT NULL REFERENCES add_ons (id),
    price NUMERIC(10, 2) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rate_code_addon_pricing_pair ON rate_code_addon_pricing (rate_code_id, add_on_id);

CREATE TABLE IF NOT EXISTS customer_event_pricing (
    id BIGSERIAL PRIMARY KEY,
    customer_pricing_profile_id BIGINT NOT NULL REFERENCES customer_pricing_profiles (id),
    event_type_id BIGINT NOT NULL REFERENCES event_types (id),
    base_price NUMERIC(10, 2) NOT NULL,
    hourly_rate NUMERIC(10, 2) NOT NULL,
    price_per_guest NUMERIC(10, 2) NOT NULL DEFAULT 0.00
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_event_pricing_pair ON customer_event_pricing (customer_pricing_profile_id, event_type_id);

CREATE TABLE IF NOT EXISTS customer_addon_pricing (
    id BIGSERIAL PRIMARY KEY,
    customer_pricing_profile_id BIGINT NOT NULL REFERENCES customer_pricing_profiles (id),
    add_on_id BIGINT NOT NULL REFERENCES add_ons (id),
    price NUMERIC(10, 2) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_addon_pricing_pair ON customer_addon_pricing (customer_pricing_profile_id, add_on_id);

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

INSERT INTO system_settings (id, operational_date)
VALUES (1, CURRENT_DATE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Birthday Celebration', 'Private theater birthday party with decorations', 2999.00, 500.00, 0.00, 2, 6, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Birthday Celebration' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Anniversary Special', 'Romantic anniversary celebration setup', 3499.00, 600.00, 0.00, 2, 5, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Anniversary Special' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Surprise Proposal', 'Elegant proposal setup with premium decorations', 4999.00, 700.00, 0.00, 2, 4, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Surprise Proposal' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'HD Screening', 'Private HD movie screening experience', 1999.00, 400.00, 0.00, 2, 6, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'HD Screening' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Corporate Event', 'Professional corporate meeting or presentation', 3999.00, 800.00, 0.00, 2, 8, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Corporate Event' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Baby Shower', 'Themed baby shower celebration', 3499.00, 500.00, 0.00, 2, 5, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Baby Shower' AND binge_id IS NULL
);

INSERT INTO event_types (name, description, base_price, hourly_rate, price_per_guest, min_hours, max_hours, active)
SELECT 'Custom Event', 'Create your own custom event experience', 2499.00, 500.00, 0.00, 1, 8, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM event_types WHERE name = 'Custom Event' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Basic Decoration', 'Balloons and ribbons', 499.00, 'DECORATION', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Basic Decoration' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Premium Decoration', 'Premium themed decoration with LED lights', 1499.00, 'DECORATION', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Premium Decoration' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Flower Decoration', 'Fresh flower arrangements', 999.00, 'DECORATION', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Flower Decoration' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Soft Drinks Pack', '6 assorted cold drinks', 299.00, 'BEVERAGE', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Soft Drinks Pack' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Premium Beverage Pack', 'Mocktails and fresh juices', 599.00, 'BEVERAGE', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Premium Beverage Pack' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Photo Shoot (30 min)', 'Professional photography session', 1999.00, 'PHOTOGRAPHY', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Photo Shoot (30 min)' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Photo + Video Shoot', 'Photos and cinematic video coverage', 3999.00, 'PHOTOGRAPHY', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Photo + Video Shoot' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Fog Effect', 'Dramatic fog machine effects', 799.00, 'EFFECT', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Fog Effect' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Red Carpet Entry', 'VIP red carpet welcome', 999.00, 'EFFECT', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Red Carpet Entry' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Confetti Blast', 'Confetti cannon celebration', 499.00, 'EFFECT', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Confetti Blast' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Birthday Cake (1 kg)', 'Custom designer cake', 799.00, 'FOOD', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Birthday Cake (1 kg)' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Premium Cake (2 kg)', 'Premium multi-tier designer cake', 1499.00, 'FOOD', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Premium Cake (2 kg)' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Snacks Platter', 'Assorted finger food and snacks', 699.00, 'FOOD', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Snacks Platter' AND binge_id IS NULL
);

INSERT INTO add_ons (name, description, price, category, active)
SELECT 'Live Music (1 hour)', 'Acoustic live performance', 2999.00, 'EXPERIENCE', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM add_ons WHERE name = 'Live Music (1 hour)' AND binge_id IS NULL
);