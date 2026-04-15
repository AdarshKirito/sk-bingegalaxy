-- V12: Seat/Room Selection, Loyalty/Rewards, Dynamic/Surge Pricing

-- ═══════════════════════════════════════════════════════════
--  VENUE ROOMS
-- ═══════════════════════════════════════════════════════════

CREATE TABLE venue_rooms (
    id              BIGSERIAL PRIMARY KEY,
    binge_id        BIGINT NOT NULL,
    name            VARCHAR(100) NOT NULL,
    room_type       VARCHAR(30) NOT NULL,
    capacity        INTEGER NOT NULL DEFAULT 1,
    description     VARCHAR(500),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_venue_room_binge ON venue_rooms (binge_id);

-- Booking: room assignment
ALTER TABLE bookings ADD COLUMN venue_room_id BIGINT;
ALTER TABLE bookings ADD COLUMN venue_room_name VARCHAR(100);

-- ═══════════════════════════════════════════════════════════
--  LOYALTY ACCOUNTS & TRANSACTIONS
-- ═══════════════════════════════════════════════════════════

CREATE TABLE loyalty_accounts (
    id                    BIGSERIAL PRIMARY KEY,
    customer_id           BIGINT NOT NULL,
    binge_id              BIGINT NOT NULL,
    total_points_earned   BIGINT NOT NULL DEFAULT 0,
    current_balance       BIGINT NOT NULL DEFAULT 0,
    tier_level            VARCHAR(20) NOT NULL DEFAULT 'BRONZE',
    version               BIGINT DEFAULT 0,
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_loyalty_customer_binge UNIQUE (customer_id, binge_id)
);

CREATE TABLE loyalty_transactions (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES loyalty_accounts(id),
    booking_ref     VARCHAR(20),
    type            VARCHAR(10) NOT NULL,
    points          BIGINT NOT NULL,
    description     VARCHAR(255),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_loyalty_txn_account ON loyalty_transactions (account_id);
CREATE INDEX idx_loyalty_txn_booking ON loyalty_transactions (booking_ref) WHERE booking_ref IS NOT NULL;

-- Booking: loyalty points tracking
ALTER TABLE bookings ADD COLUMN loyalty_points_earned BIGINT NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN loyalty_points_redeemed BIGINT NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN loyalty_discount_amount DECIMAL(10,2);

-- Binge: loyalty program config
ALTER TABLE binges ADD COLUMN loyalty_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE binges ADD COLUMN loyalty_points_per_rupee INTEGER NOT NULL DEFAULT 10;
ALTER TABLE binges ADD COLUMN loyalty_redemption_rate INTEGER NOT NULL DEFAULT 100;

-- ═══════════════════════════════════════════════════════════
--  SURGE PRICING RULES
-- ═══════════════════════════════════════════════════════════

CREATE TABLE surge_pricing_rules (
    id              BIGSERIAL PRIMARY KEY,
    binge_id        BIGINT NOT NULL,
    name            VARCHAR(100) NOT NULL,
    day_of_week     INTEGER,
    start_minute    INTEGER NOT NULL,
    end_minute      INTEGER NOT NULL,
    multiplier      DECIMAL(5,2) NOT NULL,
    label           VARCHAR(100),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_surge_binge ON surge_pricing_rules (binge_id);

-- Booking: surge pricing snapshot
ALTER TABLE bookings ADD COLUMN surge_multiplier DECIMAL(5,2);
ALTER TABLE bookings ADD COLUMN surge_label VARCHAR(100);
