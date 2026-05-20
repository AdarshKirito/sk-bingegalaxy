-- =============================================================================
-- V40: Temporary slot holds (pre-payment reservations)
--
--   • A SlotHold reserves a (binge, date, start, duration) for a customer for a
--     short TTL (default 7 minutes) while they enter payment details.
--   • Holds are taken into account by the conflict / capacity checks in
--     BookingService so two customers cannot bypass each other.
--   • A scheduler expires stale holds every minute (releases the slot back).
--   • A successful booking marks the hold CONVERTED with the booking ref.
-- =============================================================================

CREATE TABLE IF NOT EXISTS slot_holds (
    id                      BIGSERIAL    PRIMARY KEY,
    hold_token              VARCHAR(64)  NOT NULL,
    binge_id                BIGINT       NOT NULL,
    customer_id             BIGINT       NOT NULL,
    customer_name           VARCHAR(150),
    customer_email          VARCHAR(150),
    event_type_id           BIGINT       NOT NULL,
    booking_date            DATE         NOT NULL,
    start_time              TIME         NOT NULL,
    duration_minutes        INT          NOT NULL,
    number_of_guests        INT          NOT NULL DEFAULT 1,
    venue_room_id           BIGINT,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    expires_at              TIMESTAMP    NOT NULL,
    released_at             TIMESTAMP,
    release_reason          VARCHAR(80),
    converted_booking_ref   VARCHAR(32),
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_slot_hold_status
        CHECK (status IN ('ACTIVE','CONVERTED','RELEASED','EXPIRED')),
    CONSTRAINT chk_slot_hold_duration
        CHECK (duration_minutes BETWEEN 30 AND 720)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_slot_holds_token
    ON slot_holds (hold_token);

CREATE INDEX IF NOT EXISTS idx_slot_holds_status_expiry
    ON slot_holds (status, expires_at);

CREATE INDEX IF NOT EXISTS idx_slot_holds_binge_date
    ON slot_holds (binge_id, booking_date);

CREATE INDEX IF NOT EXISTS idx_slot_holds_customer
    ON slot_holds (customer_id, status);

ALTER TABLE slot_holds
    ADD CONSTRAINT fk_slot_holds_event_type
    FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE CASCADE;
